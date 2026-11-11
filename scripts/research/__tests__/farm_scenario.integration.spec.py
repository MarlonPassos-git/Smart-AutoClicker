"""Verifica o grafo Android por frames/estados determinísticos, sem executar ADB."""

import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("farm", ROOT / "scripts/research/configure_farm_scenario.py")
farm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(farm)


class ScenarioRunner:
    """Interpreta somente contratos existentes de condições/ações para verificar o artefato gerado."""

    def __init__(self, document, answers=None):
        # Match the repository's real persistence/query contract, including trigger ordering by name.
        self.events = sorted(document["scenario"]["events"], key=lambda event: (
            event["event"]["type"] != "TRIGGER_EVENT",
            event["event"]["name"] if event["event"]["type"] == "TRIGGER_EVENT" else event["event"]["priority"],
        ))
        self.counters = {c["name"]: c["startingValue"] for c in document["scenario"]["counters"]}
        self.counters.update(answers or {})
        self.enabled = {e["event"]["id"] for e in self.events if e["event"].get("enabledOnStart", True)}
        self.clicks = []
        self.notifications = []
        self.zooms = []
        self.elapsed_ms = 0
        self.detected = {}

    @staticmethod
    def compare(left, operation, right):
        return {"EQUALS": left == right, "LOWER": left < right, "LOWER_OR_EQUALS": left <= right,
                "GREATER": left > right, "GREATER_OR_EQUALS": left >= right}[operation]

    def matches(self, wrapped, frame):
        condition = wrapped["condition"]
        kind = condition["type"]
        if kind == "ON_COUNTER_REACHED":
            target = self.counters[condition["counterOperationCounterName"]] if condition["counterOperationValueType"] == "COUNTER" else condition["counterValue"]
            return self.compare(self.counters[condition["counterName"]], condition["counterComparisonOperation"], target)
        if kind == "ON_TIMER_REACHED":
            return frame.get("tick", False)
        if kind == "ON_NUMBER_DETECTED":
            area = tuple(condition[key] for key in ("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"))
            value = frame.get("numbers", {}).get(area)
            position = ((area[0] + area[2]) / 2, (area[1] + area[3]) / 2, area[2] - area[0], area[3] - area[1])
            if condition.get("numberFormatType") == "POSITIVE_QUANTITY":
                quantities = sorted((q for q in frame.get("quantities", []) if q[0] > 0 and int(q[0]) == q[0]), key=lambda q: q[1])
                if not quantities:
                    return False
                value, x, y = quantities[0]
                position = (x, y, 40, 30)
            if value is None:
                return False
            target = self.counters[condition["numberCounterOperationCounterName"]] if condition["numberCounterOperationValueType"] == "COUNTER" else condition["numberCounterValue"]
            fulfilled = self.compare(value, condition["numberCounterComparisonOperation"], target)
            if fulfilled:
                self.detected[condition["id"]] = (value, position)
            return fulfilled
        if kind == "ON_COLOR_DETECTED":
            area = tuple(condition[key] for key in ("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"))
            actual = frame.get("colors", {}).get(area)
            if actual is None:
                return False
            expected = condition["colorRgba"]
            # Native color conditions compare mean absolute RGB distance, in percent.
            difference = sum(abs(((actual >> shift) & 255) - ((expected >> shift) & 255)) for shift in (16, 8, 0))
            return difference * 100 / (255 * 3) <= condition["threshold"]
        if kind == "ON_TEXT_DETECTED":
            area = tuple(condition[key] for key in ("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"))
            alternatives = [condition["textToDetect"]]
            encoded = condition.get("textToDetectAlternatives", "v1:")[3:]
            while encoded:
                length, encoded = encoded.split(":", 1)
                length = int(length)
                alternatives.append(encoded[:length])
                encoded = encoded[length:]
            return frame.get("texts", {}).get(area) in alternatives
        if kind == "ON_IMAGE_DETECTED":
            fulfilled = condition["path"] in frame.get("images", set())
            if fulfilled:
                area = [condition[key] for key in ("areaLeft", "areaTop", "areaRight", "areaBottom")]
                position = frame.get("image_positions", {}).get(condition["path"],
                    ((area[0]+area[2])/2, (area[1]+area[3])/2, area[2]-area[0], area[3]-area[1]))
                self.detected[condition["id"]] = (None, position)
            return fulfilled
        return False

    def execute(self, event):
        for action in event["actions"]:
            value = action["action"]
            kind = value["type"]
            if kind == "CHANGE_COUNTER":
                name = value["counterName"]
                operand = value["counterOperationValueType"]
                if operand == "COUNTER":
                    amount = self.counters[value["counterOperationCounterName"]]
                elif operand == "CONDITION":
                    result = self.detected.get(value["counterOperationConditionId"])
                    if result is None:
                        continue
                    number, (x, y, width, height) = result
                    amount = {"CONDITION_NUMBER": number, "CONDITION_LEFT": x-width/2, "CONDITION_RIGHT": x+width/2,
                              "CONDITION_TOP": y-height/2, "CONDITION_BOTTOM": y+height/2,
                              "CONDITION_CENTER_X": x, "CONDITION_CENTER_Y": y}[value["counterOperationConditionPositionType"]]
                    if amount is None:
                        continue
                else:
                    amount = value["counterOperationValue"]
                self.counters[name] = {"ADD": self.counters[name] + amount, "MINUS": self.counters[name] - amount, "SET": amount}[value["counterOperation"]]
            elif kind == "TOGGLE_EVENT":
                if value.get("toggleAll"):
                    if value["toggleAllType"] == "DISABLE":
                        self.enabled.clear()
                else:
                    for toggle in action["eventsToggle"]:
                        if toggle["type"] == "ENABLE":
                            self.enabled.add(toggle["toggleEventId"])
                        else:
                            self.enabled.discard(toggle["toggleEventId"])
            elif kind == "NOTIFICATION":
                self.notifications.append(value["notificationMessageText"])
            elif kind == "ZOOM":
                self.zooms.append(value)
            elif kind == "CLICK":
                self.clicks.append(value["name"])
                self.elapsed_ms += farm.action_duration(action)
            elif kind == "PAUSE":
                self.elapsed_ms += value["pauseDuration"]

    def frame(self, *, images=(), numbers=None, texts=None, colors=None, tick=False, quantities=(), image_positions=None):
        frame = dict(images=set(images), numbers=numbers or {}, texts=texts or {}, colors=colors or {}, tick=tick,
                     quantities=quantities, image_positions=image_positions or {})
        for event_type in ("TRIGGER_EVENT", "IMAGE_EVENT"):
            for event in self.events:
                config = event["event"]
                if config["type"] != event_type or config["id"] not in self.enabled:
                    continue
                self.detected = {}
                results = []
                for condition in event["conditions"]:
                    result = self.matches(condition, frame)
                    results.append(result)
                    if (config["conditionOperator"] == 1 and not result) or (config["conditionOperator"] != 1 and result):
                        break
                fulfilled = all(results) if config["conditionOperator"] == 1 else any(results)
                if results and fulfilled:
                    self.execute(event)
                    if event_type == "IMAGE_EVENT" and not config.get("keepDetecting", False):
                        break


class FarmScenarioTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original, cls.assets = farm.read_backup(ROOT / "farm-seis-herois.zip")
        cls.document, cls.updated_assets = farm.configure(cls.original, cls.assets)
        cls.by_prefix = {e["event"]["name"][:2]: e for e in cls.original["scenario"]["events"]}
        cls.village_image = cls.by_prefix["04"]["conditions"][0]["condition"]["path"]

    def new_runner(self, **answers):
        return ScenarioRunner(self.document, answers)

    def enter(self, runner, resource="gold"):
        runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS[resource]: farm.CAPACITIES[resource]})
        runner.frame(images=[farm.asset_path("builder-1")])
        runner.frame()
        runner.frame(images=[farm.asset_path("wall-18")])

    @staticmethod
    def selected_frame(runner, gold=10_000_000, elixir=10_000_000, cost=farm.WALL_COST):
        runner.frame(images=[farm.asset_path("wall-title"), farm.asset_path("selected-18")], numbers={
            (1305, 762, 1437, 790): cost, (1515, 762, 1647, 790): cost,
            farm.RESOURCE_AREAS["gold"]: gold, farm.RESOURCE_AREAS["elixir"]: elixir,
        }, colors={area: farm.PRICE_COLORS[balance >= cost]
                   for resource, balance in (("gold", gold), ("elixir", elixir))
                   for area in farm.PRICE_COLOR_AREAS[resource]})

    def open_batch(self, runner, gold=30_500_000, elixir=29_000_000):
        self.enter(runner, "gold" if gold >= farm.CAPACITIES["gold"] else "elixir")
        for _ in range(8):
            runner.frame(images=[farm.asset_path(name) for name in ("wall-title", "selected-18", "wall-more")],
                         numbers={(1305, 762, 1437, 790): farm.WALL_COST, (1515, 762, 1647, 790): farm.WALL_COST,
                                  farm.RESOURCE_AREAS["gold"]: gold, farm.RESOURCE_AREAS["elixir"]: elixir},
                         colors={area: farm.PRICE_COLORS[balance >= farm.WALL_COST]
                                 for resource, balance in (("gold", gold), ("elixir", elixir))
                                 for area in farm.PRICE_COLOR_AREAS[resource]})
            if runner.counters["muros_etapa"] in (14, 15):
                return
        self.fail("Batch selection did not open")

    @staticmethod
    def batch_frame(runner, gold, elixir, actual_total=None):
        total = runner.counters["muros_lote_total"] if actual_total is None else actual_total
        runner.frame(images=[farm.asset_path(name) for name in ("wall-batch-remove", "wall-batch-add", "selected-18")],
                     numbers={**{area: total for area in farm.BATCH_PRICE_AREAS.values()},
                              farm.RESOURCE_AREAS["gold"]: gold, farm.RESOURCE_AREAS["elixir"]: elixir},
                     colors={area: farm.PRICE_COLORS[balance >= total]
                             for resource, balance in (("gold", gold), ("elixir", elixir))
                             for area in farm.BATCH_PRICE_COLOR_AREAS[resource]})

    def test_full_storages_form_one_affordable_batch_per_resource_without_rescanning_each_wall(self):
        runner = self.new_runner()
        self.open_batch(runner)
        zooms = len(runner.zooms)
        for _ in range(90):
            self.batch_frame(runner, 30_500_000, 29_000_000)
            if runner.counters["muros_etapa"] == 24:
                break
        self.assertEqual(7, runner.counters["muros_lote_quantidade"])
        self.assertEqual(29_400_000, runner.counters["muros_lote_total"])
        self.assertEqual(1, runner.clicks.count("Remover muro excedente do lote"))
        self.assertEqual(1, runner.clicks.count("Abrir melhoria do lote em gold"))
        self.assertEqual(zooms, len(runner.zooms))
        frame = dict(images=[farm.asset_path("wall-batch-confirm"), farm.asset_path("wall-batch-ok")],
                     numbers={farm.BATCH_CONFIRM_COST_AREA: 29_400_000})
        runner.frame(**frame, texts={farm.BATCH_CONFIRM_RESOURCE_AREA: "Elixir"})
        self.assertNotIn("Confirmar melhoria do lote verificado", runner.clicks)
        runner.frame(**frame, texts={farm.BATCH_CONFIRM_RESOURCE_AREA: "Ouro"})
        self.assertEqual(1, runner.clicks.count("Confirmar melhoria do lote verificado"))
        self.assertEqual(0, runner.counters["muros_melhorados"])
        runner.frame(images=[farm.asset_path("wall-title"), farm.asset_path("selected-19")])
        self.assertEqual(7, runner.counters["muros_melhorados"])

        # A second selection uses elixir after the first resource cannot pay another wall.
        runner.frame(images=[farm.asset_path("builder-1")])
        runner.frame()
        runner.frame(images=[farm.asset_path("wall-18")])
        for _ in range(8):
            runner.frame(images=[farm.asset_path(name) for name in ("wall-title", "selected-18", "wall-more")],
                         numbers={(1305, 762, 1437, 790): farm.WALL_COST, (1515, 762, 1647, 790): farm.WALL_COST},
                         colors={area: farm.PRICE_COLORS[balance >= farm.WALL_COST]
                                 for resource, balance in (("gold", 1_100_000), ("elixir", 29_000_000))
                                 for area in farm.PRICE_COLOR_AREAS[resource]})
            if runner.counters["muros_etapa"] == 15:
                break
        for _ in range(90):
            self.batch_frame(runner, 1_100_000, 29_000_000)
            if runner.counters["muros_etapa"] == 25:
                break
        self.assertEqual(6, runner.counters["muros_lote_quantidade"])
        self.assertEqual(25_200_000, runner.counters["muros_lote_total"])
        self.assertEqual(1, runner.clicks.count("Abrir melhoria do lote em elixir"))
        runner.frame(images=[farm.asset_path("wall-batch-confirm"), farm.asset_path("wall-batch-ok")],
                     numbers={farm.BATCH_CONFIRM_COST_AREA: 25_200_000},
                     texts={farm.BATCH_CONFIRM_RESOURCE_AREA: "Elixir"})
        runner.frame(images=[farm.asset_path("wall-title"), farm.asset_path("selected-19")])
        self.assertEqual(13, runner.counters["muros_melhorados"])
        self.assertEqual(2, runner.clicks.count("Confirmar melhoria do lote verificado"))

    def test_batch_respects_reserve_and_requires_matching_total_before_confirmation(self):
        runner = self.new_runner(reserva_ouro=10_000_000)
        self.open_batch(runner)
        for _ in range(70):
            self.batch_frame(runner, 30_500_000, 29_000_000)
            if runner.counters["muros_etapa"] == 24:
                break
        self.assertEqual(4, runner.counters["muros_lote_quantidade"])
        self.assertEqual(16_800_000, runner.counters["muros_lote_total"])
        runner.frame(images=[farm.asset_path("wall-batch-confirm"), farm.asset_path("wall-batch-ok")],
                     numbers={farm.BATCH_CONFIRM_COST_AREA: 21_000_000},
                     texts={farm.BATCH_CONFIRM_RESOURCE_AREA: "Ouro"})
        self.assertNotIn("Confirmar melhoria do lote verificado", runner.clicks)
        self.assertNotIn("Remover muro excedente do lote", runner.clicks)

    def test_lost_addition_keeps_actual_batch_size_and_unreadable_price_stops_without_payment(self):
        runner = self.new_runner()
        self.open_batch(runner)
        for _ in range(8):
            self.batch_frame(runner, 30_500_000, 29_000_000, farm.WALL_COST)
            if runner.counters["muros_etapa"] == 24:
                break
        self.assertEqual(1, runner.counters["muros_lote_quantidade"])
        self.assertEqual(farm.WALL_COST, runner.counters["muros_lote_total"])
        self.assertEqual(1, runner.clicks.count("Adicionar muro ao lote"))
        runner = self.new_runner()
        self.open_batch(runner)
        for _ in range(10):
            runner.frame(tick=True)
        self.assertTrue(runner.notifications)
        self.assertNotIn("Confirmar melhoria do lote verificado", runner.clicks)

    def test_destruction_below_exactly_above_and_invalid(self):
        for detected, should_stop in ((74, False), (75, True), (90, True), (None, False)):
            with self.subTest(detected=detected):
                runner = self.new_runner(destruicao_alvo=75)
                runner.frame(numbers={farm.PERCENT_AREA: detected})
                self.assertEqual(should_stop, "Encerrar batalha" in runner.clicks)
        runner = self.new_runner(finalizar_partida=2)
        runner.frame(numbers={farm.PERCENT_AREA: 100})
        self.assertNotIn("Encerrar batalha", runner.clicks)

    def test_visible_destruction_does_not_starve_exit_confirmation(self):
        runner = self.new_runner(destruicao_alvo=50)
        runner.frame(numbers={farm.PERCENT_AREA: 59})
        before_confirmation = len(runner.clicks)
        confirmation = farm.asset_path("battle-exit-ok")
        runner.frame(images=[confirmation], numbers={farm.PERCENT_AREA: 59})
        self.assertEqual(["Confirmar encerramento"], runner.clicks[before_confirmation:])
        runner.frame(images=[self.by_prefix["01"]["conditions"][0]["condition"]["path"]])
        runner.frame(numbers={farm.PERCENT_AREA: 50})
        self.assertEqual("Encerrar batalha", runner.clicks[-1])
        event = next(e for e in runner.events if e["event"]["name"].startswith("03."))
        condition = event["conditions"][0]["condition"]
        self.assertEqual(3, condition["detectionType"])
        self.assertLess(condition["detectionAreaLeft"], condition["areaLeft"])
        self.assertLess(condition["detectionAreaTop"], condition["areaTop"])
        self.assertGreater(condition["detectionAreaRight"], condition["areaRight"])
        self.assertGreater(condition["detectionAreaBottom"], condition["areaBottom"])

    def test_troop_selection_uses_quantity_position_without_fixed_slot_or_portrait(self):
        troop = next(e for e in self.document["scenario"]["events"] if e["event"]["name"].startswith("08."))
        self.assertEqual("POSITIVE_QUANTITY", troop["conditions"][0]["condition"]["numberFormatType"])
        selection = next(a for e in self.document["scenario"]["events"] for a in e["actions"]
                         if a["action"]["name"] == "Selecionar card pela quantidade reconhecida")
        motion = selection["gestureFingers"][0]["motions"][0]
        self.assertEqual(("COUNTER", "tropas_card_x", "tropas_card_y"),
                         (motion["xCoordinateType"], motion["xCounter"], motion["yCounter"]))

    def test_either_resource_full_enters_before_search(self):
        for resource in ("gold", "elixir"):
            runner = self.new_runner()
            runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS[resource]: farm.CAPACITIES[resource]})
            self.assertEqual(1, runner.counters["muros_etapa"])
            self.assertNotIn("Abrir batalhas", runner.clicks)
        runner = self.new_runner()
        runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS[r]: farm.CAPACITIES[r] for r in ("gold", "elixir")})
        self.assertEqual(1, runner.counters["muros_etapa"])

    def test_full_resource_entry_uses_the_same_village_text_fallback_as_farming(self):
        village_text = next(c["condition"] for c in self.by_prefix["04"]["conditions"]
                            if c["condition"]["type"] == "ON_TEXT_DETECTED")
        area = tuple(village_text[key] for key in ("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"))
        for resource in ("gold", "elixir"):
            runner = self.new_runner()
            runner.frame(texts={area: village_text["textToDetect"]},
                         numbers={farm.RESOURCE_AREAS[resource]: farm.CAPACITIES[resource]})
            self.assertEqual(1, runner.counters["muros_etapa"])
            self.assertNotIn("Abrir batalhas", runner.clicks)

    def test_return_waits_for_village_resource_animation_before_checking_full_balance(self):
        runner = self.new_runner()
        result_image = self.by_prefix["01"]["conditions"][0]["condition"]["path"]
        runner.frame(images=[result_image])
        self.assertIn("Voltar à vila", runner.clicks)
        # The calibrated return animation needs this settling window before its counters are authoritative.
        balance = farm.CAPACITIES["gold"] if runner.elapsed_ms >= 3000 else farm.CAPACITIES["gold"] / 2
        runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS["gold"]: balance})
        self.assertEqual(1, runner.counters["muros_etapa"])
        self.assertNotIn("Abrir batalhas", runner.clicks)

    def test_disabled_walls_follow_selected_storage_stop_without_dark_elixir(self):
        runner = self.new_runner(melhorar_muros=0, parar_farm_quando=4)
        runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS[r]: farm.CAPACITIES[r] for r in ("gold", "elixir")})
        self.assertTrue(runner.notifications)
        self.assertFalse(runner.enabled)

    def test_loot_mode_requires_both_opponent_balances_to_be_read_as_zero(self):
        for gold, elixir, destruction, expected in ((1, 0, 99, False), (0, 1, 99, False),
                                                   (None, 0, 99, False), (0, None, 99, False),
                                                   (0, 0, None, False), (0, 0, 12, True)):
            with self.subTest(gold=gold, elixir=elixir, destruction=destruction):
                runner = self.new_runner(finalizar_partida=1)
                runner.frame(numbers={farm.LOOT_AREAS["gold"]: gold, farm.LOOT_AREAS["elixir"]: elixir,
                                      farm.PERCENT_AREA: destruction})
                self.assertEqual(expected, "Encerrar batalha" in runner.clicks)

    def test_natural_end_never_requests_exit_from_percentage_or_empty_loot(self):
        runner = self.new_runner(finalizar_partida=2)
        runner.frame(numbers={farm.PERCENT_AREA: 100, **{area: 0 for area in farm.LOOT_AREAS.values()}})
        self.assertNotIn("Encerrar batalha", runner.clicks)
        runner.frame(images=[self.by_prefix["01"]["conditions"][0]["condition"]["path"]])
        self.assertIn("Voltar à vila", runner.clicks)

    def test_wall_timing_rules_distinguish_full_storage_and_affordable_balance(self):
        balances = [(0, 0), (farm.WALL_COST - 1, 0), (farm.WALL_COST, 0), (0, farm.WALL_COST),
                    (farm.CAPACITIES["gold"], 0), (0, farm.CAPACITIES["elixir"]),
                    (farm.CAPACITIES["gold"], farm.CAPACITIES["elixir"])]
        for rule in range(1, 6):
            for gold, elixir in balances:
                with self.subTest(rule=rule, gold=gold, elixir=elixir):
                    full_gold = gold == farm.CAPACITIES["gold"]
                    full_elixir = elixir == farm.CAPACITIES["elixir"]
                    expected = {1: full_gold, 2: full_elixir, 3: full_gold or full_elixir,
                                4: full_gold and full_elixir, 5: max(gold, elixir) > 0}[rule]
                    runner = self.new_runner(quando_melhorar_muros=rule)
                    runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS["gold"]: gold,
                                                                     farm.RESOURCE_AREAS["elixir"]: elixir})
                    self.assertEqual(1 if expected else 0, runner.counters["muros_etapa"])
                    if expected:
                        self.assertNotIn("Abrir batalhas", runner.clicks)

    def test_selected_storage_stop_is_independent_from_wall_timing_and_only_in_village(self):
        for rule in range(5):
            for full_gold, full_elixir in ((False, False), (True, False), (False, True), (True, True)):
                with self.subTest(rule=rule, gold=full_gold, elixir=full_elixir):
                    expected = {0: False, 1: full_gold, 2: full_elixir,
                                3: full_gold or full_elixir, 4: full_gold and full_elixir}[rule]
                    numbers = {farm.RESOURCE_AREAS["gold"]: farm.CAPACITIES["gold"] if full_gold else 0,
                               farm.RESOURCE_AREAS["elixir"]: farm.CAPACITIES["elixir"] if full_elixir else 0}
                    runner = self.new_runner(melhorar_muros=0, parar_farm_quando=rule)
                    runner.frame(numbers=numbers)
                    self.assertFalse(runner.notifications)
                    runner.frame(images=[self.village_image], numbers=numbers)
                    self.assertEqual(expected, bool(runner.notifications))
                    self.assertEqual(not expected, bool(runner.enabled))

    def test_wall_entry_precedes_storage_stop_and_disabled_walls_never_enter(self):
        numbers = {farm.RESOURCE_AREAS[r]: farm.CAPACITIES[r] for r in ("gold", "elixir")}
        runner = self.new_runner(parar_farm_quando=4)
        runner.frame(images=[self.village_image], numbers=numbers)
        self.assertEqual(1, runner.counters["muros_etapa"])
        self.assertFalse(runner.notifications)
        runner = self.new_runner(melhorar_muros=0, quando_melhorar_muros=5)
        runner.frame(images=[self.village_image], numbers=numbers)
        self.assertEqual(0, runner.counters["muros_etapa"])
        self.assertIn("Abrir batalhas", runner.clicks)

    def test_form_declares_conditional_fields_and_keeps_resource_priority_internal(self):
        counters = {c["name"]: c for c in self.document["scenario"]["counters"]}
        self.assertEqual(dict(counterName="finalizar_partida", value=0), counters["destruicao_alvo"]["startQuestion"]["visibleWhen"])
        self.assertEqual(dict(counterName="melhorar_muros", value=1), counters["quando_melhorar_muros"]["startQuestion"]["visibleWhen"])
        self.assertNotIn("startQuestion", counters["prioridade_recursos"])
        self.assertEqual(0, counters["prioridade_recursos"]["startingValue"])

    def test_generated_events_obey_native_import_condition_contract(self):
        screen = {"ON_IMAGE_DETECTED", "ON_NUMBER_DETECTED", "ON_TEXT_DETECTED", "ON_COLOR_DETECTED"}
        triggers = {"ON_COUNTER_REACHED", "ON_TIMER_REACHED", "ON_BROADCAST_RECEIVED"}
        events = self.document["scenario"]["events"]
        ids = {e["event"]["id"] for e in events}
        for event in events:
            with self.subTest(event=event["event"]["name"]):
                self.assertTrue(event["actions"])
                self.assertTrue(event["conditions"])
                allowed = screen if event["event"]["type"] == "IMAGE_EVENT" else triggers
                self.assertTrue(all(c["condition"]["type"] in allowed for c in event["conditions"]))
                self.assertTrue(all(t["toggleEventId"] in ids for a in event["actions"] for t in a.get("eventsToggle", [])))

    def test_priority_fallback_and_normal_insufficient_balance_exit(self):
        for preference, gold, elixir, expected in (
            (0, 10_000_000, 10_000_000, "gold"), (1, 10_000_000, 10_000_000, "elixir"),
            (0, 1_000_000, 10_000_000, "elixir"), (1, 10_000_000, 1_000_000, "gold"),
            (0, 1_000_000, 1_000_000, None),
        ):
            with self.subTest(preference=preference, gold=gold, elixir=elixir):
                runner = self.new_runner(prioridade_recursos=preference)
                self.enter(runner)
                for _ in range(5):
                    self.selected_frame(runner, gold, elixir)
                if expected:
                    self.assertIn(f"Abrir melhoria em {expected}", runner.clicks)
                    self.assertEqual(1, sum(name.startswith("Abrir melhoria em") for name in runner.clicks))
                else:
                    self.assertEqual(0, runner.counters["muros_etapa"])
                    self.assertFalse(runner.notifications)
                    runner.frame(images=[self.village_image])
                    self.assertIn("Abrir batalhas", runner.clicks)

    def test_available_gray_price_glyphs_allow_payment_and_still_reject_red(self):
        for color, should_pay in ((-2763307, True), (-2723221, False)):
            with self.subTest(color=color):
                runner = self.new_runner()
                self.enter(runner)
                for _ in range(4):
                    runner.frame(images=[farm.asset_path("wall-title"), farm.asset_path("selected-18")],
                                 numbers={(1305, 762, 1437, 790): farm.WALL_COST, (1515, 762, 1647, 790): farm.WALL_COST},
                                 colors={area: color for areas in farm.PRICE_COLOR_AREAS.values() for area in areas})
                self.assertEqual(should_pay, "Abrir melhoria em gold" in runner.clicks)

    def test_confirmation_requires_resource_cost_and_result_before_repeating(self):
        runner = self.new_runner()
        self.enter(runner)
        for _ in range(3):
            self.selected_frame(runner)
        self.assertEqual(9, runner.counters["muros_etapa"])
        images = [farm.asset_path("confirmation-19"), farm.asset_path("gold-confirm")]
        colors = {area: farm.CONFIRM_PRICE_COLOR for area in farm.CONFIRM_PRICE_COLOR_AREAS}
        runner.frame(images=images, numbers={(1425, 924, 1620, 958): farm.WALL_COST + 1, farm.RESOURCE_AREAS["gold"]: 10_000_000}, colors=colors)
        self.assertNotIn("Confirmar melhoria verificada", runner.clicks)
        runner.frame(images=[farm.asset_path("confirmation-19"), farm.asset_path("elixir-confirm")],
                     numbers={(1425, 924, 1620, 958): farm.WALL_COST}, colors=colors)
        self.assertNotIn("Confirmar melhoria verificada", runner.clicks)
        runner.frame(images=images, numbers={(1425, 924, 1620, 958): farm.WALL_COST}, colors={})
        self.assertNotIn("Confirmar melhoria verificada", runner.clicks)
        runner.frame(images=images, numbers={(1425, 924, 1620, 958): farm.WALL_COST}, colors=colors)
        self.assertIn("Confirmar melhoria verificada", runner.clicks)
        self.assertEqual(0, runner.counters["muros_melhorados"])
        runner.frame(images=[farm.asset_path("wall-title"), farm.asset_path("selected-19")])
        self.assertEqual(1, runner.counters["muros_melhorados"])
        self.assertEqual(1, runner.counters["muros_etapa"])

    def test_insufficient_red_gold_switches_to_available_elixir_in_both_brightnesses(self):
        for red in (-3839646, -30593, -161409):
            with self.subTest(red=red):
                runner = self.new_runner()
                self.enter(runner)
                colors = {area: red for area in farm.PRICE_COLOR_AREAS["gold"]}
                colors.update({area: -1381654 for area in farm.PRICE_COLOR_AREAS["elixir"]})
                for _ in range(6):
                    runner.frame(images=[farm.asset_path("wall-title"), farm.asset_path("selected-18")],
                                 numbers={(1305, 762, 1437, 790): farm.WALL_COST,
                                          (1515, 762, 1647, 790): farm.WALL_COST}, colors=colors)
                self.assertNotIn("Abrir melhoria em gold", runner.clicks)
                self.assertIn("Abrir melhoria em elixir", runner.clicks)
                self.assertFalse(runner.notifications)

    def test_confirmation_accepts_dark_and_bright_green_prices_but_rejects_other_colors(self):
        for color, available in ((-10894070, True), (-9175283, True), (-8914156, True),
                                 (-65536, False), (-1, False), (-16777216, False), (-3084668, False)):
            with self.subTest(color=color):
                runner = self.new_runner()
                self.enter(runner)
                for _ in range(3):
                    self.selected_frame(runner)
                runner.frame(images=[farm.asset_path("confirmation-19"), farm.asset_path("gold-confirm")],
                             numbers={(1425, 924, 1620, 958): farm.WALL_COST},
                             colors={area: color for area in farm.CONFIRM_PRICE_COLOR_AREAS})
                self.assertEqual(available, "Confirmar melhoria verificada" in runner.clicks)

    def test_repeated_upgrades_exhaust_preferred_resource_then_fallback_without_balance_ocr(self):
        runner = self.new_runner(muros_etapa=1)
        balances = {"gold": 2 * farm.WALL_COST, "elixir": farm.WALL_COST}
        for completed, resource in enumerate(("gold", "gold", "elixir"), start=1):
            runner.frame(images=[farm.asset_path("builder-1")])
            runner.frame()
            runner.frame(images=[farm.asset_path("wall-18")])
            for _ in range(5):
                self.selected_frame(runner, **balances)
            self.assertEqual(9 if resource == "gold" else 10, runner.counters["muros_etapa"])
            runner.frame(images=[farm.asset_path("confirmation-19"), farm.asset_path(f"{resource}-confirm")],
                         numbers={(1425, 924, 1620, 958): farm.WALL_COST},
                         colors={area: farm.CONFIRM_PRICE_COLOR for area in farm.CONFIRM_PRICE_COLOR_AREAS})
            runner.frame(images=[farm.asset_path("wall-title"), farm.asset_path("selected-19")])
            self.assertEqual(completed, runner.counters["muros_melhorados"])
            balances[resource] -= farm.WALL_COST
        runner.frame(images=[farm.asset_path("builder-1")])
        runner.frame()
        runner.frame(images=[farm.asset_path("wall-18")])
        for _ in range(5):
            self.selected_frame(runner, **balances)
        self.assertEqual(0, runner.counters["muros_etapa"])
        self.assertFalse(runner.notifications)
        runner.frame(images=[self.village_image])
        self.assertIn("Abrir batalhas", runner.clicks)

    def test_missing_builder_targets_and_recognition_stop_with_reason(self):
        runner = self.new_runner()
        runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS["gold"]: farm.CAPACITIES["gold"]})
        runner.frame(numbers={(2047, 815, 2076, 855): 0})
        self.assertIn("construtor", runner.notifications[-1])
        self.assertFalse(runner.enabled)
        for phase in (2, 3):
            runner = self.new_runner()
            self.enter(runner)
            runner.counters["muros_etapa"] = phase
            for _ in range(40):
                runner.frame(images=[farm.asset_path("builder-1")], tick=True)
            self.assertFalse(runner.enabled)
            self.assertIn("muro elegível" if phase == 2 else "reconhecimento", runner.notifications[-1])

    def test_four_zoomed_sectors_are_bounded_and_last_sector_can_find_a_target(self):
        for finds_target in (False, True):
            with self.subTest(finds_target=finds_target):
                runner = self.new_runner(muros_etapa=1)
                for _ in range(50):
                    images = [farm.asset_path("builder-1")]
                    if finds_target and runner.counters["muros_setor"] == 3:
                        images.append(farm.asset_path("wall-18"))
                    runner.frame(images=images, tick=True)
                    if runner.counters["muros_etapa"] == 3 or not runner.enabled:
                        break
                centers = [(z["zoomCenterX"], z["zoomCenterY"]) for z in runner.zooms if z["zoomDirection"] == "IN"]
                self.assertEqual(4, len(centers))
                self.assertEqual(list(farm.WALL_SCAN_CENTERS), centers)
                if finds_target:
                    self.assertEqual(3, runner.counters["muros_etapa"])
                    self.assertEqual(["Selecionar o muro reconhecido"], runner.clicks)
                    self.assertFalse(runner.notifications)
                else:
                    self.assertFalse(runner.enabled)
                    self.assertIn("muro elegível", runner.notifications[-1])
                    self.assertFalse(runner.clicks)

    def test_multiple_free_builders_and_end_observation_between_deployment_blocks(self):
        for count in range(2, 7):
            runner = self.new_runner(muros_etapa=1)
            runner.frame(texts={(2045, 795, 2140, 860): f"{count}/6"})
            runner.frame()
            self.assertEqual(2, runner.counters["muros_etapa"])
        runner = self.new_runner(destruicao_alvo=75)
        self.prepare_army(runner)
        for _ in range(5):
            runner.frame(quantities=[(29, 550, 945), (11, 1420, 945)], numbers={farm.PERCENT_AREA: 10}, texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
        clicks_before = len(runner.clicks)
        self.assertGreater(clicks_before, 0)
        runner.frame(numbers={farm.PERCENT_AREA: 75}, quantities=[(29, 550, 945)], texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
        self.assertEqual("Encerrar batalha", runner.clicks[-1])
        self.assertEqual(0, runner.clicks[clicks_before:].count("Lançar uma unidade"))
        self.assertEqual(0, runner.counters["tropas_etapa"])

    def test_deployment_yields_and_retains_heroes_spell_ring_and_assets(self):
        generated = self.document["scenario"]["events"]
        for event in generated:
            if event["event"]["name"].startswith(("08.", "10.")):
                self.assertLessEqual(sum(farm.action_duration(a) for a in event["actions"]), 1000)
                self.assertFalse(event["event"]["keepDetecting"])
        hero = next(e for e in generated if e["event"]["name"].startswith("09."))
        self.assertEqual(self.by_prefix["09"]["conditions"], hero["conditions"])
        self.assertEqual(12, sum(a["action"]["name"].startswith("Lançar feitiço no anel") for e in generated for a in e["actions"]))
        self.assertTrue(self.assets.keys() <= self.updated_assets.keys())
        self.assertFalse(self.original["scenario"]["counters"])

    def prepare_army(self, runner, hero_x=945):
        runner.frame()
        runner.frame(images=[self.by_prefix["07"]["conditions"][0]["condition"]["path"]])
        hero = self.by_prefix["09"]["conditions"][0]["imageReferences"][0]["path"]
        runner.frame(images=[hero], image_positions={hero: (hero_x, 980, 85, 55)},
                     quantities=[(29, 550, 945), (11, 1420, 945)])

    def test_quantities_bound_clicks_for_one_or_multiple_troop_types_including_event_card(self):
        for counts, first_x, hero_x in (([20], 550, 945), ([10], 550, 945), ([29], 550, 945), ([25, 29], 550, 945), ([1, 7, 13], 550, 945),
                                        ([25, 2, 5, 8, 3, 2, 4, 4, 1, 1], 170, 1450)):
            with self.subTest(counts=counts):
                runner = self.new_runner(finalizar_partida=2)
                self.prepare_army(runner, hero_x=hero_x)
                remaining = list(counts)
                for _ in range(300):
                    before = runner.counters["tropas_cliques"]
                    quantities = [(amount, first_x + index * 115, 945) for index, amount in enumerate(remaining)] + [(11, 2140, 945)]
                    runner.frame(quantities=quantities, texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
                    clicks = runner.counters["tropas_cliques"] - before
                    self.assertLessEqual(clicks, max(farm.DEPLOYMENT_BURSTS))
                    if clicks:
                        first = next(index for index, amount in enumerate(remaining) if amount)
                        remaining[first] -= clicks
                    if runner.counters["tropas_etapa"] == 0:
                        break
                self.assertEqual([0] * len(counts), remaining)
                self.assertEqual(sum(counts), runner.clicks.count("Lançar uma unidade"))
                self.assertEqual(len(counts), runner.clicks.count("Selecionar card pela quantidade reconhecida"))
                self.assertFalse(runner.notifications)

    def test_twenty_or_ten_units_are_sent_in_fast_batches_without_observing_each_click(self):
        for count in (20, 10):
            runner = self.new_runner(finalizar_partida=2)
            self.prepare_army(runner)
            remaining = count
            start_ms = runner.elapsed_ms
            frames_with_clicks = 0
            for _ in range(20):
                before = runner.counters["tropas_cliques"]
                runner.frame(quantities=[(remaining, 550, 945), (11, 1420, 945)], texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
                clicks = runner.counters["tropas_cliques"] - before
                if clicks:
                    frames_with_clicks += 1
                    remaining -= clicks
                if not remaining:
                    break
            self.assertEqual(count, runner.clicks.count("Lançar uma unidade"))
            self.assertLessEqual(frames_with_clicks, 3)
            self.assertLessEqual(runner.elapsed_ms - start_ms, 1000)

    def test_missed_touches_are_recovered_from_the_remaining_card_quantity(self):
        runner = self.new_runner(finalizar_partida=2)
        self.prepare_army(runner)
        remaining = 25
        missed = 2
        for _ in range(60):
            before = runner.counters["tropas_cliques"]
            runner.frame(quantities=[(remaining, 550, 945), (11, 1420, 945)],
                         texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
            clicks = runner.counters["tropas_cliques"] - before
            if clicks:
                remaining -= clicks - missed
                missed = 0
            if runner.counters["tropas_etapa"] == 0:
                break
        self.assertEqual(0, remaining)
        self.assertEqual(27, runner.clicks.count("Lançar uma unidade"))
        self.assertEqual(2, runner.clicks.count("Selecionar card pela quantidade reconhecida"))
        self.assertFalse(runner.notifications)

    def test_fast_batches_obey_the_total_click_limit_without_overshooting_it(self):
        runner = self.new_runner(finalizar_partida=2)
        self.prepare_army(runner)
        remaining = 601
        for _ in range(200):
            before = runner.counters["tropas_cliques"]
            runner.frame(quantities=[(remaining, 550, 945)], texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
            remaining -= runner.counters["tropas_cliques"] - before
            if not runner.enabled:
                break
        self.assertEqual(600, runner.clicks.count("Lançar uma unidade"))
        self.assertEqual(1, remaining)
        self.assertIn("limite de tentativas", runner.notifications[-1])

    def test_unchanged_quantity_stops_after_three_retries_instead_of_clicking_forever(self):
        runner = self.new_runner(finalizar_partida=2)
        self.prepare_army(runner)
        for _ in range(100):
            runner.frame(quantities=[(2, 550, 945), (11, 1420, 945)], texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
            if not runner.enabled:
                break
        self.assertFalse(runner.enabled)
        self.assertIn("não diminuiu", runner.notifications[-1])
        self.assertEqual(6, runner.clicks.count("Lançar uma unidade"))

    def test_village_navigation_is_disabled_during_deployment_and_restored_after_result(self):
        runner = self.new_runner(finalizar_partida=2, melhorar_muros=0)
        self.prepare_army(runner)
        navigation_ids = {self.by_prefix[name]["event"]["id"] for name in ("04", "05", "06")}
        self.assertTrue(navigation_ids.isdisjoint(runner.enabled))
        result = self.by_prefix["01"]["conditions"][0]["condition"]["path"]
        runner.frame(images=[result])
        self.assertTrue(navigation_ids <= runner.enabled)
        runner.frame(images=[self.village_image])
        self.assertIn("Abrir batalhas", runner.clicks)

    def test_deployment_requires_the_attack_interface_before_every_selection_and_batch(self):
        runner = self.new_runner(finalizar_partida=2)
        self.prepare_army(runner)
        for _ in range(6):
            runner.frame(quantities=[(100, 550, 945), (11, 1420, 945)], texts={farm.BATTLE_INTERFACE_AREA: "Saque"})
            if runner.clicks.count("Lançar uma unidade"):
                break
        before = runner.clicks.count("Lançar uma unidade")
        for _ in range(10):
            runner.frame(tick=True)
        self.assertEqual(before, runner.clicks.count("Lançar uma unidade"))
        self.assertFalse(runner.enabled)
        self.assertIn("interface de ataque", runner.notifications[-1])

    def test_hero_sequence_has_shorter_waits_and_retains_calibrated_placement_alternatives(self):
        hero = next(e for e in self.document["scenario"]["events"] if e["event"]["name"].startswith("09."))
        self.assertLess(sum(farm.action_duration(a) for a in hero["actions"]), 1000)
        self.assertLessEqual(hero["event"]["detectionCooldownMs"], 200)
        self.assertEqual(4, sum(a["action"]["name"] == "Lançar o herói — posição alternativa" for a in hero["actions"]))

    def test_wall_cost_is_captured_and_final_confirmation_must_match_the_current_cost(self):
        runner = self.new_runner()
        self.enter(runner)
        for _ in range(5):
            self.selected_frame(runner, cost=5_000_000)
        self.assertEqual(5_000_000, runner.counters["muros_custo_gold"])
        frame = dict(images=[farm.asset_path("confirmation-19"), farm.asset_path("gold-confirm")],
                     colors={area: farm.CONFIRM_PRICE_COLOR for area in farm.CONFIRM_PRICE_COLOR_AREAS})
        runner.frame(**frame, numbers={(1425, 924, 1620, 958): farm.WALL_COST})
        self.assertNotIn("Confirmar melhoria verificada", runner.clicks)
        runner.frame(**frame, numbers={(1425, 924, 1620, 958): 5_000_000})
        self.assertIn("Confirmar melhoria verificada", runner.clicks)

    def test_wall_reserve_preserves_gold_and_uses_the_other_resource_when_allowed(self):
        runner = self.new_runner(reserva_ouro=8_000_000)
        self.enter(runner)
        for _ in range(12):
            self.selected_frame(runner)
        self.assertNotIn("Abrir melhoria em gold", runner.clicks)
        self.assertIn("Abrir melhoria em elixir", runner.clicks)

    def test_insufficient_balances_for_both_reserves_resume_farm_without_reentering_same_visit(self):
        runner = self.new_runner(quando_melhorar_muros=5, reserva_ouro=8_000_000, reserva_elixir=8_000_000)
        self.enter(runner)
        for _ in range(12):
            self.selected_frame(runner)
        self.assertEqual(0, runner.counters["muros_etapa"])
        self.assertFalse(any(name.startswith("Abrir melhoria") for name in runner.clicks))
        for _ in range(3):
            runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS["gold"]: 10_000_000})
        self.assertEqual(0, runner.counters["muros_etapa"])
        self.assertIn("Abrir batalhas", runner.clicks)
        self.assertFalse(runner.notifications)

    def test_minimum_balance_stops_only_in_village_and_zero_disables_each_limit(self):
        for resource, parameter in (("gold", "parar_ouro_minimo"), ("elixir", "parar_elixir_minimo")):
            for threshold, expected in ((0, False), (500_000, True)):
                runner = self.new_runner(**{parameter: threshold})
                numbers = {farm.RESOURCE_AREAS[resource]: 224_923}
                runner.frame(numbers=numbers)
                self.assertFalse(runner.notifications)
                runner.frame(images=[self.village_image], numbers=numbers)
                self.assertEqual(expected, bool(runner.notifications))
                if expected:
                    self.assertIn("mínimo", runner.notifications[-1])
                    self.assertEqual(0, runner.counters["muros_etapa"])

    def test_storage_capacities_can_be_configured_and_advanced_fields_are_initially_collapsed(self):
        runner = self.new_runner(capacidade_ouro=12_000_000)
        runner.frame(images=[self.village_image], numbers={farm.RESOURCE_AREAS["gold"]: 12_000_000})
        self.assertEqual(1, runner.counters["muros_etapa"])
        counters = {c["name"]: c for c in self.document["scenario"]["counters"]}
        self.assertEqual(0, counters["configuracoes_avancadas"]["startingValue"])
        for name in ("reserva_ouro", "reserva_elixir", "parar_ouro_minimo", "parar_elixir_minimo", "capacidade_ouro", "capacidade_elixir"):
            self.assertEqual(dict(counterName="configuracoes_avancadas", value=1), counters[name]["startQuestion"]["visibleWhen"])


if __name__ == "__main__":
    unittest.main()
