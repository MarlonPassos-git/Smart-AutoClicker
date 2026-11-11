"""Gera o cenário Android parametrizado sem executar ações via ADB.

Exemplo: python scripts/research/configure_farm_scenario.py farm-seis-herois.zip farm-parametrizado.zip
"""

import argparse
import hashlib
from copy import deepcopy
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


PROFILE_DIR = Path(__file__).parent / "profiles/walls-sm-s916b"
PERCENT_AREA = (2140, 815, 2250, 880)
LOOT_AREAS = {"gold": (170, 130, 410, 182), "elixir": (170, 188, 410, 240)}
RESOURCE_AREAS = {"gold": (1910, 25, 2180, 90), "elixir": (1910, 98, 2180, 163),
                  "dark": (1960, 171, 2180, 236)}
CAPACITIES = {"gold": 30_500_000, "elixir": 29_000_000, "dark": 470_000}
WALL_COST = 4_200_000
QUANTITY_AREA = (80, 920, 2260, 970)
# Overlapping OCR windows preserve the small labels across a full-width army bar.
QUANTITY_THRESHOLD = 80
BATTLE_INTERFACE_AREA = (160, 88, 430, 138)
DEPLOYMENT_POINTS = ((800, 120), (1540, 120), (600, 260), (1740, 260), (400, 400), (1940, 400),
                     (250, 560), (2090, 560), (450, 700), (1890, 700), (650, 840), (1690, 840))
DEPLOYMENT_BURSTS = (16, 8, 4, 2, 1)
WALL_SCAN_CENTERS = ((750, 300), (1350, 300), (750, 700), (1350, 700))
# Center each calibrated range between its observed bright and dim glyphs; white and red remain disjoint.
PRICE_COLORS = {True: -1381654, False: -1934991}
PRICE_COLOR_AREAS = {
    "gold": ((1334, 768, 1337, 771), (1400, 775, 1403, 778)),
    "elixir": ((1544, 768, 1547, 771), (1610, 775, 1613, 778)),
}
# Midpoint of the dark and bright green price glyphs observed on the confirmation button.
CONFIRM_PRICE_COLOR = -10034676
CONFIRM_PRICE_THRESHOLD = 7
BATCH_PRICE_AREAS = {"gold": (1290, 755, 1440, 795), "elixir": (1500, 755, 1650, 795)}
BATCH_PRICE_COLOR_AREAS = {"gold": ((1424, 777, 1427, 780), (1346, 772, 1349, 775)),
                         "elixir": ((1634, 777, 1637, 780), (1556, 772, 1559, 775))}
BATCH_CONFIRM_COST_AREA = (1160, 488, 1360, 532)
BATCH_CONFIRM_RESOURCE_AREA = (1340, 488, 1500, 532)
BATCH_MAX_WALLS = 50
CONFIRM_PRICE_COLOR_AREAS = ((1474, 928, 1477, 931), (1552, 932, 1555, 935))


def asset_path(asset):
    """Nome compatível com o importador, derivado do conteúdo; exemplo: asset_path('wall-18')."""
    digest = hashlib.sha256((PROFILE_DIR / f"{asset}.png").read_bytes()).digest()
    return f"Condition_{int.from_bytes(digest[:8], 'big') & ((1 << 63) - 1)}.png"


def read_backup(path):
    """Lê exatamente um cenário e suas imagens; exemplo: read_backup(Path('farm.zip'))."""
    with ZipFile(path) as archive:
        names = [name for name in archive.namelist() if name.endswith(".json")]
        if len(names) != 1:
            raise ValueError(f"backup={path}: esperado um cenário, encontrados {len(names)}")
        prefix = names[0].rsplit("/", 1)[0] + "/"
        return json.loads(archive.read(names[0])), {
            name[len(prefix):]: archive.read(name) for name in archive.namelist()
            if name.startswith(prefix) and name.endswith(".png")
        }


class ScenarioBuilder:
    """Aloca identificadores sem substituir os existentes; exemplo: ScenarioBuilder(document)."""

    def __init__(self, document):
        self.document = deepcopy(document)
        self.scenario = self.document["scenario"]
        self.events = self.scenario["events"]
        self.scenario_id = self.scenario["scenario"]["id"]
        self.next_id = max(self._ids(self.scenario), default=0)
        self.assets = {}

    @staticmethod
    def _ids(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key == "id" and isinstance(item, int):
                    yield item
                else:
                    yield from ScenarioBuilder._ids(item)
        elif isinstance(value, list):
            for item in value:
                yield from ScenarioBuilder._ids(item)

    def identifier(self):
        self.next_id += 1
        return self.next_id

    def event(self, name, *, trigger=False, enabled=False, priority=0):
        event = dict(event=dict(id=self.identifier(), scenarioId=self.scenario_id, name=name,
                               priority=priority, conditionOperator=1, enabledOnStart=enabled,
                               type="TRIGGER_EVENT" if trigger else "IMAGE_EVENT", keepDetecting=False,
                               detectionCooldownMs=0), conditions=[], actions=[])
        self.events.append(event)
        return event

    def condition(self, event, name, kind, **fields):
        condition = dict(id=self.identifier(), eventId=event["event"]["id"], name=name,
                         type=kind, priority=len(event["conditions"]), **fields)
        wrapped = dict(condition=condition)
        event["conditions"].append(wrapped)
        return wrapped

    def number(self, event, name, area, operation, value, *, counter=False, number_format="COMMA_DECIMAL", threshold=80):
        return self.condition(event, name, "ON_NUMBER_DETECTED", threshold=threshold, shouldBeDetected=True,
                              **dict(zip(("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"), area)),
                              numberCounterComparisonOperation=operation, numberFormatType=number_format,
                              numberCounterOperationValueType="COUNTER" if counter else "NUMBER",
                              **({"numberCounterOperationCounterName": value} if counter else {"numberCounterValue": value}))

    def counter_condition(self, event, name, operation, value, *, counter=False):
        return self.condition(event, name, "ON_COUNTER_REACHED", counterName=name,
                              counterComparisonOperation=operation, counterOperationValueType="COUNTER" if counter else "NUMBER",
                              **({"counterOperationCounterName": value} if counter else {"counterValue": value}))

    def color(self, event, name, area, value, threshold=10):
        self.condition(event, name, "ON_COLOR_DETECTED", colorRgba=value, threshold=threshold, shouldBeDetected=True,
                       **dict(zip(("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"), area)))

    def image(self, event, name, asset, area, *, search=None, threshold=12):
        path = asset_path(asset)
        self.assets[path] = (PROFILE_DIR / f"{asset}.png").read_bytes()
        fields = dict(zip(("areaLeft", "areaTop", "areaRight", "areaBottom"), area))
        if search:
            fields.update(zip(("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"), search))
        wrapped = self.condition(event, name, "ON_IMAGE_DETECTED", path=path, threshold=threshold,
                                 detectionType=3 if search else 1, shouldBeDetected=True, **fields)
        wrapped["imageReferences"] = [dict(conditionId=wrapped["condition"]["id"], priority=0, path=path,
                                           **dict(zip(("areaLeft", "areaTop", "areaRight", "areaBottom"), area)))]
        return wrapped

    def action(self, event, name, kind, **fields):
        action = dict(action=dict(id=self.identifier(), eventId=event["event"]["id"],
                                  priority=len(event["actions"]), name=name, type=kind, **fields),
                      gestureFingers=[], intentExtras=[], eventsToggle=[])
        event["actions"].append(action)
        return action

    def toggle(self, event, targets, enabled):
        action = self.action(event, "Ativar etapas" if enabled else "Desativar etapas", "TOGGLE_EVENT", toggleAll=False)
        action["eventsToggle"] = [dict(id=self.identifier(), actionId=action["action"]["id"],
                                      type="ENABLE" if enabled else "DISABLE", toggleEventId=e["event"]["id"])
                                 for e in targets]

    def set_counter(self, event, name, value, operation="SET", *, counter=False):
        self.action(event, f"{name}: {operation} {value}", "CHANGE_COUNTER", counterName=name,
                    counterOperation=operation, counterOperationValueType="COUNTER" if counter else "NUMBER",
                    **({"counterOperationCounterName": value} if counter else {"counterOperationValue": value}))

    def detected_value(self, event, name, condition, kind="CONDITION_NUMBER"):
        self.action(event, f"Guardar {name} reconhecido", "CHANGE_COUNTER", counterName=name,
                    counterOperation="SET", counterOperationValueType="CONDITION",
                    counterOperationConditionId=condition["condition"]["id"], counterOperationConditionPositionType=kind)

    def click(self, event, name, x=None, y=None, condition=None, *, counter=False, press_ms=80):
        action = self.action(event, name, "CLICK")
        finger_id = self.identifier()
        coordinates = dict(xCoordinateType="CONDITION", yCoordinateType="CONDITION", conditionId=condition["condition"]["id"]) if condition else dict(
            xCoordinateType="LITERAL", yCoordinateType="LITERAL", xLiteral=x, yLiteral=y)
        if counter:
            coordinates = dict(xCoordinateType="COUNTER", yCoordinateType="COUNTER", xCounter=x, yCounter=y)
        action["gestureFingers"] = [dict(finger=dict(id=finger_id, actionId=action["action"]["id"], index=0, startTimeMs=0), motions=[
            dict(id=self.identifier(), fingerId=finger_id, index=0, motionType="PRESS", **coordinates),
            dict(id=self.identifier(), fingerId=finger_id, index=1, motionType="WAIT", durationMs=press_ms),
            dict(id=self.identifier(), fingerId=finger_id, index=2, motionType="RELEASE"),
        ])]

    def phase(self, event, value):
        self.set_counter(event, "muros_etapa", value)
        self.set_counter(event, "muros_tentativas", 0)
        self.set_counter(event, "muros_aplicar_etapa", 1)

    def stop(self, event, reason):
        self.action(event, "Avisar motivo da parada", "NOTIFICATION", notificationMessageText=reason, notificationImportance=3)
        self.action(event, "Interromper automação", "TOGGLE_EVENT", toggleAll=True, toggleAllType="DISABLE")


def declare_parameters(builder):
    definitions = [
        ("finalizar_partida", 0, dict(title="Finalizar a partida quando", type="CHOICE", order=0,
                                    options=[dict(label="Alcançar a porcentagem de destruição", value=0),
                                             dict(label="Roubar todo o ouro e elixir", value=1),
                                             dict(label="A partida terminar", value=2)])),
        ("destruicao_alvo", 50, dict(title="Meta de destruição (%)", description="Observada entre os blocos de lançamento.",
                                   type="NUMBER", order=1, minimum=1, maximum=100,
                                   visibleWhen=dict(counterName="finalizar_partida", value=0))),
        ("melhorar_muros", 1, dict(title="Melhorar muros", description="Use a paisagem Clássica e a área de muros preparada. O cenário ajusta o zoom e procura alvos em quatro setores aproximados.", type="BOOLEAN", order=2)),
        ("quando_melhorar_muros", 3, dict(title="Quando melhorar os muros", type="CHOICE", order=3,
                                        visibleWhen=dict(counterName="melhorar_muros", value=1),
                                        options=resource_options() + [dict(label="Sempre que houver saldo suficiente", value=5)])),
        ("parar_farm_quando", 0, dict(title="Parar o farm quando", type="CHOICE", order=4,
                                    description="A melhoria de muros é tentada antes da parada.",
                                    options=[dict(label="Não parar por recursos", value=0)] + resource_options())),
        ("configuracoes_avancadas", 0, dict(title="Configurações avançadas", type="BOOLEAN", order=5,
                                           description="Reservas, limites de parada e capacidades. Os valores definidos continuam ativos ao recolher.")),
    ]
    advanced = (("reserva_ouro", "Reserva de ouro para muros", 0),
                ("reserva_elixir", "Reserva de elixir para muros", 0),
                ("parar_ouro_minimo", "Parar se o ouro chegar a", 0),
                ("parar_elixir_minimo", "Parar se o elixir chegar a", 0),
                ("capacidade_ouro", "Capacidade dos depósitos de ouro", CAPACITIES["gold"]),
                ("capacidade_elixir", "Capacidade dos depósitos de elixir", CAPACITIES["elixir"]))
    for order, (name, title, value) in enumerate(advanced, start=6):
        if name.startswith("reserva_"):
            description = "Saldo preservado nos muros. Sem saldo livre suficiente, volta ao farm. 0 não reserva saldo."
        elif name.startswith("parar_"):
            description = "Encerra o farm na vila com saldo igual ou menor que este valor. 0 desativa."
        else:
            description = "Atualize este valor ao mudar a capacidade da vila."
        definitions.append((name, value, dict(title=title, type="NUMBER", order=order, minimum=1 if value else 0,
                                             maximum=1_000_000_000_000,
                                             description=description,
                                             visibleWhen=dict(counterName="configuracoes_avancadas", value=1))))
    existing = {counter["name"] for counter in builder.scenario["counters"]}
    if existing.intersection(name for name, _, _ in definitions):
        raise ValueError("counters.name: parâmetros já declarados; esperado cenário de farm original")
    builder.scenario["counters"].extend(dict(name=name, scenarioId=builder.scenario_id, startingValue=value, startQuestion=question)
                                       for name, value, question in definitions)
    for name, value in (("prioridade_recursos", 0), ("muros_etapa", 0), ("muros_aplicar_etapa", 1),
                        ("muros_tentativas", 0), ("muros_melhorados", 0), ("muros_setor", 0), ("muros_verificados_na_visita", 0),
                        ("muros_custo_gold", 0), ("muros_custo_elixir", 0), ("muros_necessario_gold", 0), ("muros_necessario_elixir", 0),
                        ("muros_reserva_gold_ok", 1), ("muros_reserva_elixir_ok", 1),
                        ("muros_saldo_gold_lido", 1), ("muros_saldo_elixir_lido", 1),
                        ("muros_lote_quantidade", 0), ("muros_lote_total", 0), ("muros_lote_anterior", 0),
                        ("muros_lote_proximo_com_reserva", 0),
                        ("tropas_etapa", 0), ("tropas_restantes", 0), ("tropas_card_x", 0), ("tropas_card_y", 0),
                        ("tropas_limite_herois", 2300), ("tropas_cursor", 0),
                        ("tropas_cliques", 0), ("tropas_cliques_disponiveis", 600), ("tropas_sem_leitura", 0), ("tropas_anterior_quantidade", 0),
                        ("tropas_anterior_x_limite", 0), ("tropas_sem_progresso", 0)):
        builder.scenario["counters"].append(dict(name=name, scenarioId=builder.scenario_id, startingValue=value))
    builder.scenario["counters"].append(dict(name="tropas_sem_interface", scenarioId=builder.scenario_id, startingValue=0))


def resource_options():
    """Rótulos e valores estáveis para os depósitos próprios; exemplo: resource_options()."""
    return [dict(label="O ouro estiver cheio", value=1), dict(label="O elixir estiver cheio", value=2),
            dict(label="Qualquer um estiver cheio", value=3), dict(label="Ouro e elixir estiverem cheios", value=4)]


def resource_entry_events(builder, village_event, *, prefix, priority, affordable=False):
    """Expande OU/AND em condições nativas, sempre restritas à vila; exemplo: resource_entry_events(...)."""
    rules = {1: [("gold",)], 2: [("elixir",)], 3: [("gold",), ("elixir",)], 4: [("gold", "elixir")]}
    if affordable:
        rules[5] = [("gold",), ("elixir",)]
    events = {rule: [] for rule in rules}
    for rule, alternatives in rules.items():
        for resources in alternatives:
            for index, alternative in enumerate(village_event["conditions"]):
                event = builder.event(f"{prefix} — regra {rule}, {'+'.join(resources)}, vila {index + 1}",
                                      priority=priority)
                village = deepcopy(alternative)
                village["condition"].update(id=builder.identifier(), eventId=event["event"]["id"])
                for reference in village.get("imageReferences", []):
                    reference["conditionId"] = village["condition"]["id"]
                event["conditions"].append(village)
                for resource in resources:
                    amount = ("capacidade_ouro" if resource == "gold" else "capacidade_elixir")
                    builder.number(event, f"{resource}: limite para regra {rule}", RESOURCE_AREAS[resource],
                                   "GREATER" if rule == 5 else "GREATER_OR_EQUALS", 0 if rule == 5 else amount, counter=rule != 5)
                events[rule].append(event)
    return events


def action_duration(action):
    """Limite do gesto/pausa por bloco; exemplo: action_duration(wrapped_action)."""
    return action["action"].get("pauseDuration", 0) + max((
        sum(m.get("durationMs", 0) for m in finger["motions"]) + finger["finger"].get("startTimeMs", 0)
        for finger in action.get("gestureFingers", [])), default=0)


def split_deployment(builder, event, result_event, *, budget_ms=1000, repeat=False):
    original = event["actions"]
    chunks = [[]]
    duration = 0
    for action in original:
        cost = action_duration(action)
        if duration + cost > budget_ms and chunks[-1]:
            chunks.append([])
            duration = 0
        chunks[-1].append(action)
        duration += cost
    if len(chunks) == 1:
        return []
    continuations = [builder.event(f"{event['event']['name']} — bloco {index + 2}", priority=event["event"]["priority"])
                     for index in range(len(chunks) - 1)]
    chain = [event] + continuations
    for index, (block, actions) in enumerate(zip(chain, chunks)):
        block["actions"] = actions
        for action in actions:
            action["action"]["eventId"] = block["event"]["id"]
        if index:
            builder.number(block, "Ataque em andamento", PERCENT_AREA, "GREATER_OR_EQUALS", 0)
            # Only the first block selects a card through its image condition. Later blocks retain selection.
            for action in actions:
                for finger in action.get("gestureFingers", []):
                    if any(m.get("conditionId") for m in finger["motions"]):
                        raise ValueError(f"action={action['action']['name']}: referência de condição fora do primeiro bloco")
        builder.toggle(block, [block], False)
        if index + 1 < len(chain):
            builder.toggle(block, [chain[index + 1]], True)
        elif repeat:
            builder.toggle(block, [event], True)
    builder.toggle(result_event, continuations, False)
    return continuations


def build_counted_deployment(builder, by_prefix):
    """Lê xN e esgota o orçamento em lotes rápidos, conferindo o ataque entre lotes."""
    screens = {phase: [] for phase in range(6)}

    def control(name, phase, priority=-62):
        event = builder.event(f"Tropas. {name}", trigger=True, enabled=True, priority=priority)
        builder.counter_condition(event, "muros_etapa", "EQUALS", 0)
        builder.counter_condition(event, "tropas_etapa", "EQUALS", phase)
        return event

    def finish(event):
        builder.set_counter(event, "tropas_etapa", 0)
        builder.toggle(event, [by_prefix["09"], by_prefix["10"]], True)

    def battle_visible(event):
        builder.condition(event, "Interface de ataque visível", "ON_TEXT_DETECTED", shouldBeDetected=True,
                          threshold=80, textAlphabet="LATIN", textToDetect="Saque",
                          **dict(zip(("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"), BATTLE_INTERFACE_AREA)))

    start = by_prefix["07"]
    navigation = [by_prefix[name] for name in ("04", "05", "06")]
    builder.toggle(start, navigation, False)
    builder.toggle(by_prefix["01"], navigation, True)
    builder.set_counter(start, "tropas_etapa", 1)
    builder.set_counter(start, "tropas_sem_interface", 0)
    builder.set_counter(start, "tropas_cliques_disponiveis", 600)
    for name in ("tropas_cliques", "tropas_cursor", "tropas_sem_leitura", "tropas_sem_progresso",
                 "tropas_anterior_quantidade", "tropas_anterior_x_limite"):
        builder.set_counter(start, name, 0)
    builder.toggle(start, [by_prefix[name] for name in ("08", "09", "10")], False)
    hero_condition = by_prefix["09"]["conditions"][0]
    hero_counters = []
    for index, reference in enumerate(hero_condition["imageReferences"]):
        counter_name = f"tropas_heroi_{index}_x"
        hero_counters.append(counter_name)
        builder.scenario["counters"].append(dict(name=counter_name, scenarioId=builder.scenario_id, startingValue=2300))
        builder.set_counter(start, counter_name, 2300)
        event = builder.event(f"07. Localizar limite dos heróis {index + 1}", priority=7.05 + index / 100)
        event["event"]["keepDetecting"] = True
        wrapped = deepcopy(hero_condition)
        wrapped["condition"].update(id=builder.identifier(), eventId=event["event"]["id"], path=reference["path"])
        for key in ("areaLeft", "areaTop", "areaRight", "areaBottom"):
            wrapped["condition"][key] = reference[key]
        wrapped["imageReferences"] = [dict(reference, conditionId=wrapped["condition"]["id"], priority=0)]
        event["conditions"] = [wrapped]
        builder.detected_value(event, counter_name, wrapped, "CONDITION_LEFT")
        screens[1].append(event)
    ready = builder.event("07. Concluir leitura da barra de tropas", priority=7.3)
    builder.number(ready, "Quantidade disponível", QUANTITY_AREA, "GREATER", 0, number_format="POSITIVE_QUANTITY", threshold=QUANTITY_THRESHOLD)
    builder.set_counter(ready, "tropas_sem_leitura", 0)
    builder.set_counter(ready, "tropas_etapa", 2)
    screens[1].append(ready)

    minimum = control("Inicializar limite dos heróis", 2, -64)
    builder.set_counter(minimum, "tropas_limite_herois", 2300)
    for index, name in enumerate(hero_counters):
        event = control(f"Comparar limite do herói {index + 1}", 2, -63.9 + index / 100)
        builder.counter_condition(event, name, "LOWER", "tropas_limite_herois", counter=True)
        builder.set_counter(event, "tropas_limite_herois", name, counter=True)
    missing = control("Limite dos heróis não reconhecido", 2, -63.5)
    builder.counter_condition(missing, "tropas_limite_herois", "GREATER_OR_EQUALS", 2300)
    builder.stop(missing, "Farm interrompido: não reconheci o limite entre tropas e heróis na barra calibrada.")
    ready = control("Começar leitura das quantidades", 2, -63.4)
    builder.counter_condition(ready, "tropas_limite_herois", "LOWER", 2300)
    builder.set_counter(ready, "tropas_etapa", 3)

    read = by_prefix["08"]
    read["event"].update(name="08. Ler a próxima quantidade disponível", detectionCooldownMs=0)
    read["conditions"], read["actions"] = [], []
    quantity = builder.number(read, "Quantidade positiva do card", QUANTITY_AREA, "GREATER", 0, number_format="POSITIVE_QUANTITY", threshold=QUANTITY_THRESHOLD)
    for name, kind in (("tropas_restantes", "CONDITION_NUMBER"), ("tropas_card_x", "CONDITION_CENTER_X"),
                       ("tropas_card_y", "CONDITION_CENTER_Y")):
        builder.detected_value(read, name, quantity, kind)
    builder.set_counter(read, "tropas_sem_leitura", 0)
    builder.set_counter(read, "tropas_sem_interface", 0)
    builder.set_counter(read, "tropas_etapa", 4)
    screens[3].append(read)

    for name, counter_name, operation, target in (("Novo card", "tropas_card_x", "GREATER", "tropas_anterior_x_limite"),
                                                 ("Quantidade diminuiu", "tropas_restantes", "LOWER", "tropas_anterior_quantidade")):
        event = control(name, 4)
        builder.counter_condition(event, counter_name, operation, target, counter=True)
        builder.set_counter(event, "tropas_sem_progresso", 0)
    stalled = control("Contar repetição sem progresso", 4)
    builder.counter_condition(stalled, "tropas_anterior_quantidade", "GREATER", 0)
    builder.counter_condition(stalled, "tropas_card_x", "LOWER_OR_EQUALS", "tropas_anterior_x_limite", counter=True)
    builder.counter_condition(stalled, "tropas_restantes", "GREATER_OR_EQUALS", "tropas_anterior_quantidade", counter=True)
    builder.set_counter(stalled, "tropas_sem_progresso", 1, "ADD")
    failed = control("Interromper lançamento sem progresso", 4, -61.9)
    builder.counter_condition(failed, "tropas_sem_progresso", "GREATER_OR_EQUALS", 3)
    builder.stop(failed, "Farm interrompido: a quantidade de tropas não diminuiu após três tentativas de lançamento.")
    select = builder.event("08. Selecionar card com quantidade reconhecida", priority=8.2)
    battle_visible(select)
    screens[4].append(select)
    builder.click(select, "Selecionar card pela quantidade reconhecida", "tropas_card_x", "tropas_card_y", counter=True, press_ms=20)
    builder.action(select, "Aguardar seleção da tropa", "PAUSE", pauseDuration=40)
    builder.set_counter(select, "tropas_anterior_quantidade", "tropas_restantes", counter=True)
    builder.set_counter(select, "tropas_anterior_x_limite", "tropas_card_x", counter=True)
    builder.set_counter(select, "tropas_anterior_x_limite", 60, "ADD")
    builder.set_counter(select, "tropas_sem_interface", 0)
    builder.set_counter(select, "tropas_etapa", 5)
    choose = control("Permitir seleção do card de tropas", 4, -61.8)
    builder.counter_condition(choose, "tropas_card_x", "LOWER", "tropas_limite_herois", counter=True)
    builder.counter_condition(choose, "tropas_sem_progresso", "LOWER", 3)
    builder.toggle(choose, [select], True)
    complete = control("Quantidades restantes pertencem aos feitiços", 4, -61.7)
    builder.counter_condition(complete, "tropas_card_x", "GREATER_OR_EQUALS", "tropas_limite_herois", counter=True)
    finish(complete)

    limit = control("Limitar total de tentativas", 5, -59)
    builder.counter_condition(limit, "tropas_cliques", "GREATER_OR_EQUALS", 600)
    builder.stop(limit, "Farm interrompido: limite de tentativas de lançamento de tropas atingido.")
    # Larger batches run first, so any xN is consumed exactly without another OCR per unit.
    # Each batch stays below a second; both touch states last beyond one 60 Hz game frame.
    for burst_index, count in enumerate(DEPLOYMENT_BURSTS):
        for start_index in range(len(DEPLOYMENT_POINTS)):
            event = builder.event(f"08. Lançar lote de {count} — início {start_index + 1}",
                                  priority=8.5 + burst_index / 100 + start_index / 10000)
            battle_visible(event)
            screens[5].append(event)
            gate = control(f"Permitir lote de {count}, início {start_index + 1}", 5, -56)
            builder.counter_condition(gate, "tropas_cursor", "EQUALS", start_index)
            builder.counter_condition(gate, "tropas_restantes", "GREATER_OR_EQUALS", count)
            builder.counter_condition(gate, "tropas_cliques_disponiveis", "GREATER_OR_EQUALS", count)
            builder.toggle(gate, [event], True)
            for offset in range(count):
                x, y = DEPLOYMENT_POINTS[(start_index + offset) % len(DEPLOYMENT_POINTS)]
                builder.click(event, "Lançar uma unidade", x, y, press_ms=20)
                builder.action(event, "Intervalo curto entre unidades", "PAUSE", pauseDuration=20)
            builder.set_counter(event, "tropas_restantes", count, "MINUS")
            builder.set_counter(event, "tropas_cliques", count, "ADD")
            builder.set_counter(event, "tropas_cliques_disponiveis", count, "MINUS")
            builder.set_counter(event, "tropas_cursor", (start_index + count) % len(DEPLOYMENT_POINTS))
            builder.set_counter(event, "tropas_sem_interface", 0)
    refresh = control("Conferir a quantidade restante", 5, -54)
    builder.counter_condition(refresh, "tropas_restantes", "LOWER_OR_EQUALS", 0)
    builder.set_counter(refresh, "tropas_sem_leitura", 0)
    builder.set_counter(refresh, "tropas_etapa", 3)
    timeout = control("Aguardar quantidade legível", 3, -60)
    builder.condition(timeout, "Intervalo para atualizar a barra", "ON_TIMER_REACHED", timerValueMs=200, restartWhenReached=True)
    builder.set_counter(timeout, "tropas_sem_leitura", 1, "ADD")
    empty = control("Barra sem outras quantidades de tropas", 3, -59.5)
    builder.counter_condition(empty, "tropas_sem_leitura", "GREATER_OR_EQUALS", 4)
    finish(empty)
    for phase in (4, 5):
        waiting = control(f"Aguardar interface na etapa {phase}", phase, -60)
        builder.condition(waiting, "Intervalo sem interface", "ON_TIMER_REACHED", timerValueMs=200, restartWhenReached=True)
        builder.set_counter(waiting, "tropas_sem_interface", 1, "ADD")
        unavailable = control(f"Interromper sem interface na etapa {phase}", phase, -59.8)
        builder.counter_condition(unavailable, "tropas_sem_interface", "GREATER_OR_EQUALS", 8)
        builder.stop(unavailable, "Farm interrompido: a interface de ataque deixou de ser reconhecida.")
    initial_wait = control("Aguardar leitura inicial da barra", 1, -60)
    builder.condition(initial_wait, "Intervalo de leitura inicial", "ON_TIMER_REACHED", timerValueMs=200, restartWhenReached=True)
    builder.set_counter(initial_wait, "tropas_sem_leitura", 1, "ADD")
    initial_failure = control("Interromper leitura inicial incompleta", 1, -59.8)
    builder.counter_condition(initial_failure, "tropas_sem_leitura", "GREATER_OR_EQUALS", 10)
    builder.stop(initial_failure, "Farm interrompido: não consegui ler a barra de tropas do ataque.")
    all_screens = [event for group in screens.values() for event in group]
    for event in (complete, empty):
        builder.toggle(event, all_screens, False)
    for phase in screens:
        gate = control(f"Aplicar etapa de tropas {phase}", phase, -120)
        builder.toggle(gate, all_screens, False)
        if screens[phase] and phase not in (4, 5):
            builder.toggle(gate, screens[phase], True)
    return [event for event in all_screens if event is not by_prefix["08"]]


def shorten_hero_deployment(event):
    """Reduz esperas mantendo as posições alternativas e a ativação da habilidade."""
    for wrapped in event["actions"]:
        action = wrapped["action"]
        if action["type"] == "PAUSE":
            action["pauseDuration"] = 120 if "habilidade" in action["name"] else 150 if "atualização" in action["name"] else 60
        for finger in wrapped.get("gestureFingers", []):
            for motion in finger["motions"]:
                if motion["motionType"] == "WAIT":
                    motion["durationMs"] = 40
    event["event"]["detectionCooldownMs"] = 150


def configure(document, images):
    """Gera uma cópia portátil com perguntas e fluxo de muros; exemplo: configure(document, images)."""
    if document.get("version") != 28 or (document.get("screenWidth"), document.get("screenHeight")) != (2340, 1080):
        raise ValueError("version/resolução: esperado backup 28 em 2340×1080")
    builder = ScenarioBuilder(document)
    base = list(builder.events)
    by_prefix = {e["event"]["name"][:2]: e for e in base}
    if set(by_prefix) != {f"{i:02d}" for i in range(11)}:
        raise ValueError("events.name: esperadas etapas de farm 00 a 10, com seis heróis e feitiços em anel")
    # Resource OCR loses digits at quality 1600 on this calibrated Samsung profile.
    builder.scenario["scenario"]["detectionQuality"] = 2340
    declare_parameters(builder)
    # Returning home animates the resource counters; observe a settled frame before opening battles.
    next(action["action"] for action in by_prefix["01"]["actions"] if action["action"]["type"] == "PAUSE")["pauseDuration"] = 3000
    builder.set_counter(by_prefix["01"], "muros_verificados_na_visita", 0)
    builder.set_counter(by_prefix["01"], "tropas_etapa", 0)
    end = by_prefix["02"]
    end["event"].update(name="02. Encerrar na meta de destruição", conditionOperator=1)
    end["conditions"] = []
    builder.number(end, "Destruição maior ou igual à meta", PERCENT_AREA, "GREATER_OR_EQUALS", "destruicao_alvo", counter=True)
    # Destruction remains visible behind the modal. Confirm it before requesting another exit.
    by_prefix["03"]["event"]["priority"] = (by_prefix["01"]["event"]["priority"] + end["event"]["priority"]) / 2
    # The old OK glyph differs in position/color. Use the current capture with a small search margin.
    by_prefix["03"]["conditions"] = []
    builder.image(by_prefix["03"], "Confirmar retirada", "battle-exit-ok", (1340, 670, 1415, 720),
                  search=(1320, 650, 1450, 740))
    # A filled clan castle adds a card before the army, moving the original fixed slot.
    by_prefix["08"]["actions"] = [action for action in by_prefix["08"]["actions"]
                                  if action["action"]["name"] != "Confirmar seleção da tropa no perfil SM-S916B"]
    continuation_events = build_counted_deployment(builder, by_prefix)
    shorten_hero_deployment(by_prefix["09"])
    continuation_events += split_deployment(builder, by_prefix["10"], by_prefix["01"], repeat=True)
    builder.toggle(end, [by_prefix[name] for name in ("08", "09", "10")] + continuation_events, False)
    builder.set_counter(end, "tropas_etapa", 0)
    builder.toggle(by_prefix["01"], continuation_events, False)
    # Own storage limits are independent of loot left in the opponent's village.
    builder.events.remove(by_prefix["00"])
    base.remove(by_prefix["00"])
    loot_end = builder.event("02. Encerrar após roubar todo o ouro e elixir", enabled=True, priority=end["event"]["priority"] + 0.1)
    builder.number(loot_end, "Ataque em andamento", PERCENT_AREA, "GREATER_OR_EQUALS", 0)
    for resource, area in LOOT_AREAS.items():
        builder.number(loot_end, f"Saque restante de {resource}", area, "EQUALS", 0)
    builder.click(loot_end, "Encerrar batalha", 210, 845)
    builder.action(loot_end, "Aguardar interface", "PAUSE", pauseDuration=400)
    builder.toggle(loot_end, [by_prefix[name] for name in ("08", "09", "10")] + continuation_events, False)
    builder.set_counter(loot_end, "tropas_etapa", 0)
    by_prefix["loot"] = loot_end
    base.append(loot_end)
    stop_groups = resource_entry_events(builder, by_prefix["04"], prefix="Farm. Parar por recursos", priority=-80)
    stop_events = [event for group in stop_groups.values() for event in group]
    for event in stop_events:
        builder.stop(event, "Farm interrompido: o limite de ouro/elixir escolhido foi atingido.")
        base.append(event)
    # Screen events cannot mix counter/trigger conditions; select each rule with native toggle gates.
    for rule in range(5):
        gate = builder.event(f"Farm. Limite de parada {rule}", trigger=True, enabled=True, priority=-5)
        builder.counter_condition(gate, "muros_etapa", "EQUALS", 0)
        builder.counter_condition(gate, "parar_farm_quando", "EQUALS", rule)
        builder.toggle(gate, stop_events, False)
        if rule:
            builder.toggle(gate, stop_groups[rule], True)
    build_minimum_balance_stops(builder, by_prefix["04"], base)
    build_wall_flow(builder, base, by_prefix, continuation_events)
    # Trigger gates precede all screen processing; screen end checks precede every deployment block.
    for index, event in enumerate(builder.events):
        event["event"].setdefault("priority", index)
        for c_index, condition in enumerate(event["conditions"]):
            condition["condition"]["priority"] = c_index
        for a_index, action in enumerate(event["actions"]):
            action["action"]["priority"] = a_index
    builder.events.sort(key=lambda event: (event["event"]["type"] != "TRIGGER_EVENT", event["event"]["priority"]))
    for index, event in enumerate(builder.events):
        event["event"]["priority"] = index
        if event["event"]["type"] == "TRIGGER_EVENT":
            # Trigger priorities are discarded by domain persistence; Room orders them by name.
            event["event"]["name"] = f"T{index:03d}. {event['event']['name']}"
    builder.document["version"] = 29
    builder.scenario["scenario"]["name"] = "Farm parametrizado — destruição e muros"
    return builder.document, {**images, **builder.assets}


def build_minimum_balance_stops(builder, village_event, base):
    """Os limites de parada são independentes das reservas usadas na melhoria de muros."""
    for resource, parameter in (("gold", "parar_ouro_minimo"), ("elixir", "parar_elixir_minimo")):
        events = []
        for index, alternative in enumerate(village_event["conditions"]):
            event = builder.event(f"Farm. Saldo mínimo de {resource} — vila {index + 1}", priority=-110)
            village = deepcopy(alternative)
            village["condition"].update(id=builder.identifier(), eventId=event["event"]["id"])
            for reference in village.get("imageReferences", []):
                reference["conditionId"] = village["condition"]["id"]
            event["conditions"].append(village)
            builder.number(event, "Saldo chegou ao mínimo configurado", RESOURCE_AREAS[resource], "LOWER_OR_EQUALS", parameter, counter=True)
            label = "ouro" if resource == "gold" else "elixir"
            builder.stop(event, f"Farm interrompido: o saldo mínimo de {label} configurado foi atingido.")
            events.append(event)
            base.append(event)
        for enabled in (False, True):
            gate = builder.event(f"Farm. Limite mínimo de {resource} {'ativo' if enabled else 'inativo'}", trigger=True, enabled=True, priority=-5)
            builder.counter_condition(gate, "muros_etapa", "EQUALS", 0)
            builder.counter_condition(gate, parameter, "GREATER" if enabled else "EQUALS", 0)
            builder.toggle(gate, events, enabled)


def build_wall_flow(builder, base, by_prefix, continuations):
    phases = {i: [] for i in range(27)}

    def stage_event(phase, name):
        event = builder.event(f"Muros {phase:02d}. {name}", priority=-100 + phase)
        phases[phase].append(event)
        return event

    def resource_available(event, resource, affordable):
        # The game marks the fixed price white/red using the actual balance. Resource OCR can drop leading digits.
        for area in PRICE_COLOR_AREAS[resource]:
            builder.color(event, f"Preço de {resource}: {'disponível' if affordable else 'insuficiente'}", area,
                          PRICE_COLORS[affordable])

    def selected(event):
        builder.image(event, "Muro selecionado", "wall-title", (970, 700, 1120, 742), threshold=18)
        builder.image(event, "Nível elegível 18", "selected-18", (1290, 700, 1362, 742), threshold=15)

    entry_groups = resource_entry_events(builder, by_prefix["04"], prefix="Muros 00. Entrar", priority=-100, affordable=True)
    phases[0] = [event for group in entry_groups.values() for event in group]
    for event in phases[0]:
        builder.set_counter(event, "muros_setor", 0)
        builder.set_counter(event, "muros_verificados_na_visita", 1)
        builder.phase(event, 1)

    for sector, (x, y) in enumerate(WALL_SCAN_CENTERS):
        event = builder.event(f"Muros. Aproximar setor {sector + 1}", trigger=True, enabled=True, priority=-70)
        builder.counter_condition(event, "muros_etapa", "EQUALS", 12)
        builder.counter_condition(event, "muros_setor", "EQUALS", sector)
        # Normalize zoom before each overlapping sector, including after selection auto-zoom.
        for _ in range(2):
            builder.action(event, "Reduzir zoom da vila ao mínimo", "ZOOM", zoomDirection="OUT", zoomIntensity=400,
                           zoomCenterX=1170, zoomCenterY=520, zoomDuration=350)
        builder.action(event, f"Aproximar setor {sector + 1}", "ZOOM", zoomDirection="IN", zoomIntensity=100,
                       zoomCenterX=x, zoomCenterY=y, zoomDuration=350)
        builder.phase(event, 2)

    free = stage_event(1, "Confirmar construtor livre pela referência calibrada")
    builder.image(free, "Um construtor livre", "builder-1", (2055, 815, 2076, 853), threshold=15)
    builder.phase(free, 12)
    additional_free = stage_event(1, "Confirmar outros construtores livres")
    # Keep numeric fractions and the font's observed slash-as-7 OCR variants separate from zero.
    fractions = [f"{n}/6" for n in range(2, 7)] + [f"{n}76]" for n in range(2, 7)]
    builder.condition(additional_free, "Construtores disponíveis", "ON_TEXT_DETECTED", shouldBeDetected=True,
                      threshold=80, textAlphabet="LATIN", textToDetect=fractions[0],
                      textToDetectAlternatives="v1:" + "".join(f"{len(text)}:{text}" for text in fractions[1:]),
                      detectionAreaLeft=2045, detectionAreaTop=795, detectionAreaRight=2140, detectionAreaBottom=860)
    builder.phase(additional_free, 12)
    busy = stage_event(1, "Sem construtor livre")
    builder.number(busy, "Construtores livres", (2047, 815, 2076, 855), "EQUALS", 0)
    builder.stop(busy, "Farm interrompido: nenhum construtor livre para melhorar muros.")

    candidate = stage_event(2, "Selecionar candidato na área calibrada")
    reference = builder.image(candidate, "Muro nível 18", "wall-18", (1450, 580, 1490, 620), search=(300, 80, 1950, 960), threshold=10)
    builder.click(candidate, "Selecionar o muro reconhecido", condition=reference)
    builder.phase(candidate, 3)

    verify = stage_event(3, "Confirmar muro e custo calibrado")
    selected(verify)
    for resource, area, reserve in (("gold", (1305, 762, 1437, 790), "reserva_ouro"),
                                     ("elixir", (1515, 762, 1647, 790), "reserva_elixir")):
        cost = builder.number(verify, f"Custo de {resource} reconhecido", area, "GREATER", 0)
        builder.detected_value(verify, f"muros_custo_{resource}", cost)
        builder.set_counter(verify, f"muros_necessario_{resource}", f"muros_custo_{resource}", counter=True)
        builder.set_counter(verify, f"muros_necessario_{resource}", reserve, "ADD", counter=True)
        builder.set_counter(verify, f"muros_reserva_{resource}_ok", 1)
        builder.set_counter(verify, f"muros_saldo_{resource}_lido", 1)
    builder.phase(verify, 13)

    for resource, reserve in (("gold", "reserva_ouro"), ("elixir", "reserva_elixir")):
        prepare = builder.event(f"Muros. Aplicar reserva de {resource}", trigger=True, enabled=True, priority=-65)
        builder.counter_condition(prepare, "muros_etapa", "EQUALS", 13)
        builder.counter_condition(prepare, "muros_aplicar_etapa", "EQUALS", 1)
        builder.counter_condition(prepare, reserve, "GREATER", 0)
        builder.set_counter(prepare, f"muros_saldo_{resource}_lido", 0)
        readings = []
        for enough in (True, False):
            balance = stage_event(13, f"Conferir reserva de {resource}: {'preservada' if enough else 'insuficiente'}")
            builder.number(balance, "Saldo e custo da próxima melhoria", RESOURCE_AREAS[resource],
                           "GREATER_OR_EQUALS" if enough else "LOWER", f"muros_necessario_{resource}", counter=True)
            builder.set_counter(balance, f"muros_reserva_{resource}_ok", int(enough))
            builder.set_counter(balance, f"muros_saldo_{resource}_lido", 1)
            readings.append(balance)
        for balance in readings:
            builder.toggle(balance, readings, False)
        inactive = builder.event(f"Muros. Sem reserva de {resource}", trigger=True, enabled=True, priority=-64.8)
        builder.counter_condition(inactive, "muros_etapa", "EQUALS", 13)
        builder.counter_condition(inactive, reserve, "EQUALS", 0)
        builder.toggle(inactive, readings, False)
    reserved = builder.event("Muros. Reservas conferidas", trigger=True, enabled=True, priority=-64.5)
    builder.counter_condition(reserved, "muros_etapa", "EQUALS", 13)
    for resource in ("gold", "elixir"):
        builder.counter_condition(reserved, f"muros_saldo_{resource}_lido", "EQUALS", 1)
    builder.toggle(reserved, phases[13], False)
    builder.phase(reserved, 4)
    for operation in ("LOWER", "GREATER"):
        mismatch = builder.event(f"Muros. Custos divergentes: {operation}", trigger=True, enabled=True, priority=-65.1)
        builder.counter_condition(mismatch, "muros_etapa", "EQUALS", 13)
        builder.counter_condition(mismatch, "muros_custo_gold", operation, "muros_custo_elixir", counter=True)
        builder.stop(mismatch, "Farm interrompido: os custos de ouro e elixir do muro não conferem.")

    for preference, phase in ((0, 5), (1, 6)):
        dispatch = builder.event(f"Muros. Prioridade {preference}", trigger=True, enabled=True, priority=-60)
        builder.counter_condition(dispatch, "muros_etapa", "EQUALS", 4)
        builder.counter_condition(dispatch, "prioridade_recursos", "EQUALS", preference)
        builder.phase(dispatch, phase)

    for phase, resource, fallback in ((5, "gold", 8), (6, "elixir", 7), (7, "gold", 0), (8, "elixir", 0)):
        protected = builder.event(f"Muros. Preservar reserva de {resource} na etapa {phase}", trigger=True, enabled=True, priority=-59)
        builder.counter_condition(protected, "muros_etapa", "EQUALS", phase)
        builder.counter_condition(protected, f"muros_reserva_{resource}_ok", "EQUALS", 0)
        if fallback == 0:
            builder.click(protected, "Sair da seleção sem gastar", 1800, 1000)
        builder.phase(protected, fallback)
        batch = stage_event(phase, f"Montar lote em {resource} com Melhorar Mais")
        selected(batch)
        resource_available(batch, resource, True)
        builder.image(batch, "Melhorar Mais disponível", "wall-more", (1090, 765, 1245, 930), threshold=15)
        builder.click(batch, "Abrir Melhorar Mais", 1170, 850)
        builder.set_counter(batch, "muros_lote_quantidade", 1)
        builder.set_counter(batch, "muros_lote_total", f"muros_custo_{resource}", counter=True)
        builder.phase(batch, 14 if resource == "gold" else 15)
        affordable = stage_event(phase, f"Pagar com {resource}")
        selected(affordable)
        resource_available(affordable, resource, True)
        builder.click(affordable, f"Abrir melhoria em {resource}", 1370 if resource == "gold" else 1585, 850)
        builder.phase(affordable, 9 if resource == "gold" else 10)
        insufficient = stage_event(phase, f"Saldo insuficiente de {resource}")
        selected(insufficient)
        resource_available(insufficient, resource, False)
        if fallback == 0:
            builder.click(insufficient, "Sair da seleção sem gastar", 1800, 1000)
        builder.phase(insufficient, fallback)

    for phase, resource in ((9, "gold"), (10, "elixir")):
        confirm = stage_event(phase, f"Confirmar upgrade com {resource}, sem gemas")
        builder.image(confirm, "Confirmação de muro para nível 19", "confirmation-19", (770, 42, 1550, 107), threshold=12)
        builder.image(confirm, f"Recurso {resource} na confirmação", f"{resource}-confirm", (1640, 922, 1700, 987), threshold=10)
        builder.number(confirm, "Custo final igual ao custo reconhecido", (1425, 924, 1620, 958), "EQUALS", f"muros_custo_{resource}", counter=True)
        for area in CONFIRM_PRICE_COLOR_AREAS:
            builder.color(confirm, "Custo final disponível", area, CONFIRM_PRICE_COLOR, threshold=CONFIRM_PRICE_THRESHOLD)
        builder.click(confirm, "Confirmar melhoria verificada", 1550, 945)
        builder.phase(confirm, 11)

    upgraded = stage_event(11, "Verificar mudança para nível 19")
    builder.image(upgraded, "Muro selecionado", "wall-title", (970, 700, 1120, 742), threshold=18)
    builder.image(upgraded, "Nível confirmado 19", "selected-19", (1290, 700, 1362, 742), threshold=15)
    builder.set_counter(upgraded, "muros_melhorados", 1, "ADD")
    builder.click(upgraded, "Desmarcar muro confirmado", 1800, 1000)
    builder.phase(upgraded, 1)

    build_wall_batches(builder, stage_event)

    # All retries are bounded, including unreadable builder/cost/balance transitions.
    timeout = builder.event("Muros. Contar tentativas de reconhecimento", trigger=True, enabled=True, priority=-30)
    builder.counter_condition(timeout, "muros_etapa", "GREATER", 0)
    builder.condition(timeout, "Intervalo de observação", "ON_TIMER_REACHED", timerValueMs=700, restartWhenReached=True)
    builder.set_counter(timeout, "muros_tentativas", 1, "ADD")
    next_sector = builder.event("Muros. Procurar no próximo setor", trigger=True, enabled=True, priority=-25)
    builder.counter_condition(next_sector, "muros_etapa", "EQUALS", 2)
    builder.counter_condition(next_sector, "muros_tentativas", "GREATER_OR_EQUALS", 6)
    builder.counter_condition(next_sector, "muros_setor", "LOWER", len(WALL_SCAN_CENTERS) - 1)
    builder.set_counter(next_sector, "muros_setor", 1, "ADD")
    builder.phase(next_sector, 1)
    no_target = builder.event("Muros. Sem alvos elegíveis", trigger=True, enabled=True, priority=-20)
    builder.counter_condition(no_target, "muros_etapa", "EQUALS", 2)
    builder.counter_condition(no_target, "muros_tentativas", "GREATER_OR_EQUALS", 6)
    builder.counter_condition(no_target, "muros_setor", "GREATER_OR_EQUALS", len(WALL_SCAN_CENTERS) - 1)
    builder.stop(no_target, "Farm interrompido: nenhum muro elegível reconhecido na área calibrada.")
    failed = builder.event("Muros. Falha de reconhecimento ou transição", trigger=True, enabled=True, priority=-10)
    builder.counter_condition(failed, "muros_etapa", "GREATER", 0)
    builder.counter_condition(failed, "muros_tentativas", "GREATER_OR_EQUALS", 6)
    builder.stop(failed, "Farm interrompido: reconhecimento ou transição de muros sem confirmação na etapa {muros_etapa} após {muros_tentativas} tentativas.")

    wall_events = [e for events in phases.values() for e in events]
    for phase in phases:
        gate = builder.event(f"Muros. Aplicar etapa {phase}", trigger=True, enabled=True, priority=-100)
        builder.counter_condition(gate, "muros_etapa", "EQUALS", phase)
        builder.counter_condition(gate, "muros_aplicar_etapa", "EQUALS", 1)
        if phase == 13:
            # Reserve setup runs before this gate clears the transition flag.
            gate["event"]["priority"] = -64.9
        builder.toggle(gate, wall_events, False)
        if phase:
            builder.toggle(gate, base + continuations, False)
            builder.toggle(gate, phases[phase], True)
        else:
            builder.toggle(gate, [event for event in base if event not in [by_prefix[f"{i:02d}"] for i in range(8, 11)]], True)
        builder.set_counter(gate, "muros_aplicar_etapa", 0)

    # These gates intentionally run every frame: they do not reset deployment progress.
    for rule in range(6):
        gate = builder.event(f"Farm. Gatilho de muros {rule}", trigger=True, enabled=True, priority=-5)
        builder.counter_condition(gate, "muros_etapa", "EQUALS", 0)
        builder.counter_condition(gate, "melhorar_muros", "EQUALS", 0 if rule == 0 else 1)
        if rule:
            builder.counter_condition(gate, "quando_melhorar_muros", "EQUALS", rule)
            builder.counter_condition(gate, "muros_verificados_na_visita", "EQUALS", 0)
        builder.toggle(gate, phases[0], False)
        if rule:
            builder.toggle(gate, entry_groups[rule], True)
    checked = builder.event("Farm. Muros já conferidos nesta visita", trigger=True, enabled=True, priority=-5)
    builder.counter_condition(checked, "muros_etapa", "EQUALS", 0)
    builder.counter_condition(checked, "muros_verificados_na_visita", "EQUALS", 1)
    builder.toggle(checked, phases[0], False)
    for mode in (0, 1, 2):
        end_gate = builder.event(f"Farm. Finalização {mode}", trigger=True, enabled=True, priority=-4)
        builder.counter_condition(end_gate, "muros_etapa", "EQUALS", 0)
        builder.counter_condition(end_gate, "finalizar_partida", "EQUALS", mode)
        builder.toggle(end_gate, [by_prefix["02"]], mode == 0)
        builder.toggle(end_gate, [by_prefix["loot"]], mode == 1)



def build_wall_batches(builder, stage_event):
    """Amplia a seleção até o saldo/reserva permitir e confirma o total; exemplo: build_wall_batches(builder, stage_event)."""
    def selection(event, resource, *, level=18, affordable=None, total="muros_lote_total"):
        if level == 18:
            builder.image(event, "Seleção de lote de muros", "wall-batch-remove", (718, 780, 755, 818), threshold=15)
        else:
            # The game collapses the batch into a single selected upgraded wall.
            builder.image(event, "Muro selecionado após o lote", "wall-title", (970, 700, 1120, 742),
                          search=(940, 680, 1150, 755), threshold=18)
        builder.image(event, f"Nível do lote {level}", f"selected-{level}", (1290, 700, 1362, 742),
                      search=(850, 690, 1470, 748), threshold=15)
        if level == 18:
            for area in BATCH_PRICE_AREAS.values():
                builder.number(event, "Custo do lote confere", area, "EQUALS", total, counter=True)
            if affordable is not None:
                for area in BATCH_PRICE_COLOR_AREAS[resource]:
                    builder.color(event, "Disponibilidade do preço do lote", area, PRICE_COLORS[affordable])

    def control(name, phase):
        event = builder.event(f"Muros. Lote. {name}", trigger=True, enabled=True, priority=-58)
        builder.counter_condition(event, "muros_etapa", "EQUALS", phase)
        return event

    for resource, reserve, ready, decide, grow, pay, shrink, confirm in (
        ("gold", "reserva_ouro", 14, 16, 18, 20, 22, 24),
        ("elixir", "reserva_elixir", 15, 17, 19, 21, 23, 25),
    ):
        available = stage_event(ready, f"Lote em {resource}: custo disponível")
        selection(available, resource, affordable=True)
        builder.set_counter(available, "muros_lote_proximo_com_reserva", "muros_lote_total", counter=True)
        builder.set_counter(available, "muros_lote_proximo_com_reserva", f"muros_custo_{resource}", "ADD", counter=True)
        builder.set_counter(available, "muros_lote_proximo_com_reserva", reserve, "ADD", counter=True)
        builder.phase(available, decide)
        unavailable = stage_event(ready, f"Lote em {resource}: remover excesso")
        selection(unavailable, resource, affordable=False)
        builder.phase(unavailable, shrink)

        # A lost +1 click or the end of the eligible row must not fabricate a larger batch.
        unchanged = stage_event(ready, f"Lote em {resource}: seleção não aumentou")
        selection(unchanged, resource, affordable=True, total="muros_lote_anterior")
        builder.set_counter(unchanged, "muros_lote_total", "muros_lote_anterior", counter=True)
        builder.set_counter(unchanged, "muros_lote_quantidade", 1, "MINUS")
        builder.phase(unchanged, pay)

        no_reserve = control(f"Ampliar {resource} sem reserva", decide)
        builder.counter_condition(no_reserve, reserve, "EQUALS", 0)
        builder.phase(no_reserve, grow)
        reserve_readings = []
        for enough in (True, False):
            balance = stage_event(decide, f"Lote em {resource}: reserva {'permite adicionar' if enough else 'atingida'}")
            selection(balance, resource, affordable=True)
            builder.number(balance, "Saldo para outro muro e reserva", RESOURCE_AREAS[resource],
                           "GREATER_OR_EQUALS" if enough else "LOWER", "muros_lote_proximo_com_reserva", counter=True)
            builder.phase(balance, grow if enough else pay)
            reserve_readings.append(balance)
        # Without a reserve, actual price colors decide affordability even when balance OCR drops digits.
        builder.toggle(no_reserve, reserve_readings, False)

        limit = control(f"Limite de seleção em {resource}", grow)
        builder.counter_condition(limit, "muros_lote_quantidade", "GREATER_OR_EQUALS", BATCH_MAX_WALLS)
        builder.phase(limit, pay)
        add = stage_event(grow, f"Adicionar um muro ao lote em {resource}")
        selection(add, resource, affordable=True)
        builder.image(add, "Adicionar um muro elegível", "wall-batch-add", (1170, 770, 1230, 855), threshold=15)
        builder.set_counter(add, "muros_lote_anterior", "muros_lote_total", counter=True)
        builder.click(add, "Adicionar muro ao lote", 1170, 850)
        builder.action(add, "Aguardar custo do lote", "PAUSE", pauseDuration=250)
        builder.set_counter(add, "muros_lote_quantidade", 1, "ADD")
        builder.set_counter(add, "muros_lote_total", f"muros_custo_{resource}", "ADD", counter=True)
        builder.phase(add, ready)

        empty = control(f"Lote unitário em {resource} deixou de ser disponível", shrink)
        builder.counter_condition(empty, "muros_lote_quantidade", "EQUALS", 1)
        builder.click(empty, "Sair do lote sem gastar", 600, 120)
        builder.phase(empty, 0)
        remove = stage_event(shrink, f"Reduzir lote em {resource} para caber no saldo")
        selection(remove, resource, affordable=False)
        builder.click(remove, "Remover muro excedente do lote", 750, 850)
        builder.action(remove, "Aguardar custo reduzido", "PAUSE", pauseDuration=250)
        builder.set_counter(remove, "muros_lote_quantidade", 1, "MINUS")
        builder.set_counter(remove, "muros_lote_total", f"muros_custo_{resource}", "MINUS", counter=True)
        builder.phase(remove, pay)

        open_confirmation = stage_event(pay, f"Abrir confirmação do lote em {resource}")
        selection(open_confirmation, resource, affordable=True)
        builder.click(open_confirmation, f"Abrir melhoria do lote em {resource}", 1370 if resource == "gold" else 1585, 850)
        builder.phase(open_confirmation, confirm)
        approved = stage_event(confirm, f"Confirmar lote em {resource} pelo total e moeda")
        builder.image(approved, "Melhorar Muros", "wall-batch-confirm", (910, 300, 1430, 365), threshold=12)
        builder.image(approved, "OK do lote", "wall-batch-ok", (1330, 668, 1420, 725), threshold=12)
        builder.number(approved, "Custo final do lote confere", BATCH_CONFIRM_COST_AREA,
                       "EQUALS", "muros_lote_total", counter=True)
        builder.condition(approved, "Moeda do lote confere", "ON_TEXT_DETECTED", shouldBeDetected=True,
                          threshold=90, textAlphabet="LATIN", textToDetect="Ouro" if resource == "gold" else "Elixir",
                          **dict(zip(("detectionAreaLeft", "detectionAreaTop", "detectionAreaRight", "detectionAreaBottom"), BATCH_CONFIRM_RESOURCE_AREA)))
        builder.click(approved, "Confirmar melhoria do lote verificado", 1380, 695)
        builder.phase(approved, 26)

    upgraded = stage_event(26, "Conferir nível 19 após melhoria do lote")
    selection(upgraded, "gold", level=19)
    builder.set_counter(upgraded, "muros_melhorados", "muros_lote_quantidade", "ADD", counter=True)
    builder.click(upgraded, "Desmarcar lote confirmado", 600, 120)
    builder.phase(upgraded, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.destination.resolve() or args.destination.exists():
        parser.error("destination: esperado arquivo novo, distinto do backup de origem")
    document, images = configure(*read_backup(args.source))
    prefix = str(document["scenario"]["scenario"]["id"])
    with ZipFile(args.destination, "w", ZIP_DEFLATED) as archive:
        archive.writestr(f"{prefix}/{prefix}.json", json.dumps(document, ensure_ascii=False))
        for name, content in images.items():
            archive.writestr(f"{prefix}/{name}", content)
    print(f"Cenário gerado: {args.destination}")


if __name__ == "__main__":
    main()
