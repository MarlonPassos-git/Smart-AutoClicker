"""Configura seis heróis e feitiços em anel nos backups de farm 27 e 28.

Uso: python scripts/research/update_farm_heroes.py farm-recursos-cheios.zip v3.6.zip farm-seis-herois.zip
"""

import argparse
from configparser import ConfigParser
from copy import deepcopy
import hashlib
import json
import math
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


DETECTION_RULES_PATH = Path(__file__).resolve().parents[2] / "config/detection.properties"
_detection_rules = ConfigParser()
_detection_rules.read_string("[detection]\n" + DETECTION_RULES_PATH.read_text())
MIN_THRESHOLD = _detection_rules.getint("detection", "screenConditionThresholdMin")
MAX_THRESHOLD = _detection_rules.getint("detection", "screenConditionThresholdMax")


def validate_threshold(value):
    """Valida tolerância compartilhada; exemplo: validate_threshold(25)."""
    if type(value) is not int or not MIN_THRESHOLD <= value <= MAX_THRESHOLD:
        raise ValueError(
            f"threshold={value!r}; esperado inteiro entre {MIN_THRESHOLD} e {MAX_THRESHOLD}"
        )
    return value


AREA_FIELDS = ("areaLeft", "areaTop", "areaRight", "areaBottom")


def read_backup(path):
    """Lê um pacote com um único cenário; exemplo: read_backup(Path('v3.6.zip'))."""
    with ZipFile(path) as archive:
        names = [name for name in archive.namelist() if name.endswith(".json")]
        if len(names) != 1:
            raise ValueError(f"backup {path}: {len(names)} JSONs; esperado um cenário")
        document = json.loads(archive.read(names[0]))
        prefix = names[0].rsplit("/", 1)[0] + "/"
        images = {
            name[len(prefix):]: archive.read(name)
            for name in archive.namelist()
            if name.startswith(prefix) and name.endswith(".png")
        }
    return document, images


def update_heroes(current, images, historical, historical_images, *, hero_threshold=20, spell_threshold=20):
    """Retorna cenário, imagens e origem das referências sem mutar a entrada.

    Exemplo: updated, assets, origins = update_heroes(current, images, old, old_assets).
    """
    validate_threshold(hero_threshold)
    validate_threshold(spell_threshold)
    version = current.get("version")
    if version not in (27, 28):
        raise ValueError(f"version={version!r}; esperado 27 ou 28")
    for label, backup in (("atual", current), ("histórico", historical)):
        resolution = (backup.get("screenWidth"), backup.get("screenHeight"))
        if resolution != (2340, 1080):
            raise ValueError(
                f"screenWidth/screenHeight ({label})={resolution!r}; "
                "esperado (2340, 1080)"
            )
    document = deepcopy(current)
    assets = dict(images)
    events = document["scenario"]["events"]
    hero_events = [
        next((event for event in events
              if event["event"]["name"] == name), None)
        for name in ("09. Lançar Duque Dracônico", "09. Lançar Campeã Real")
    ]
    if any(event is None for event in hero_events):
        names = [event["event"]["name"] for event in events]
        raise ValueError(f"events.name={names!r}; esperado etapas de Duque e Campeã")
    old_heroes = [
        event for event in historical["scenario"]["events"]
        if event["event"]["id"] in range(28, 34)
    ]
    if len(old_heroes) != 6:
        raise ValueError(f"heróis históricos={len(old_heroes)}; esperado 6")

    hero, removed_hero = hero_events
    removed_id = removed_hero["event"]["id"]
    condition = hero["conditions"][0]
    condition_id = condition["condition"]["id"]
    references = []
    provenance = []

    def add_reference(reference, source_images, label):
        content = source_images[reference["path"]]
        # Content-based names avoid the importer's cache of previous images.
        name = f"Condition_{int.from_bytes(hashlib.sha256(content).digest()[:8], 'big')}.png"
        if any(existing["path"] == name for existing in references):
            return
        assets[name] = content
        references.append(dict(
            conditionId=condition_id, priority=len(references), path=name,
            **{field: reference[field] for field in AREA_FIELDS},
        ))
        provenance.append(dict(hero=label, path=name))

    for old_hero in old_heroes:
        old_condition = old_hero["conditions"][0]
        add_reference(old_condition, historical_images, old_condition["name"])
    for hero_event in hero_events:
        for reference in hero_event["conditions"][0]["imageReferences"]:
            add_reference(reference, images, hero_event["event"]["name"])

    condition["imageReferences"] = references
    condition["condition"].update(
        name="Herói disponível — seis heróis e variações de imagem",
        path=references[0]["path"], threshold=hero_threshold, detectionType=3,
        detectionAreaLeft=0, detectionAreaTop=864,
        detectionAreaRight=2340, detectionAreaBottom=1080,
        **{field: references[0][field] for field in AREA_FIELDS},
    )
    hero["event"].update(
        name="09. Lançar heróis disponíveis e ativar habilidades",
        enabledOnStart=False, detectionCooldownMs=800,
    )
    next_action_id = max(a["action"]["id"] for e in events for a in e["actions"])
    next_finger_id = max((
        finger["finger"]["id"] for event in events for item in event["actions"]
        for finger in item.get("gestureFingers", [])
    ), default=0)
    next_motion_id = max((
        motion["id"] for event in events for item in event["actions"]
        for finger in item.get("gestureFingers", []) for motion in finger["motions"]
    ), default=0)
    hero["actions"] = []

    def action(name, kind, *, event=hero, **fields):
        nonlocal next_action_id, next_finger_id, next_motion_id
        next_action_id += 1
        fingers = []
        if kind == "CLICK" and version == 28:
            next_finger_id += 1
            position_type = fields.pop("clickPositionType")
            press = dict(motionType="PRESS")
            if position_type == "ON_DETECTED_CONDITION":
                press.update(xCoordinateType="CONDITION", yCoordinateType="CONDITION",
                             conditionId=fields.pop("clickOnConditionId"))
            else:
                press.update(xCoordinateType="LITERAL", yCoordinateType="LITERAL",
                             xLiteral=fields.pop("x"), yLiteral=fields.pop("y"))
            motions = [press, dict(motionType="WAIT", durationMs=fields.pop("pressDuration")),
                       dict(motionType="RELEASE")]
            for index, motion in enumerate(motions):
                next_motion_id += 1
                motion.update(id=next_motion_id, fingerId=next_finger_id, index=index)
            fingers.append(dict(
                finger=dict(id=next_finger_id, actionId=next_action_id, index=0, startTimeMs=0),
                motions=motions,
            ))
        wrapped = dict(
            action=dict(id=next_action_id, eventId=event["event"]["id"],
                        priority=len(event["actions"]), name=name, type=kind, **fields),
            intentExtras=[], eventsToggle=[],
        )
        if version == 28:
            wrapped["gestureFingers"] = fingers
        event["actions"].append(wrapped)

    def select(name):
        action(name, "CLICK", clickPositionType="ON_DETECTED_CONDITION",
               clickOnConditionId=condition_id, pressDuration=100)

    select("Selecionar o herói reconhecido")
    action("Aguardar seleção", "PAUSE", pauseDuration=120)
    # Alternative sides cover layouts where the first point is not deployable.
    for x, y in ((350, 650), (2100, 600), (1170, 120), (1170, 840)):
        action("Lançar o herói — posição alternativa", "CLICK",
               clickPositionType="USER_SELECTED", x=x, y=y, pressDuration=100)
        action("Aguardar lançamento", "PAUSE", pauseDuration=100)
    action("Aguardar habilidade disponível", "PAUSE", pauseDuration=250)
    select("Ativar a habilidade do herói lançado")
    action("Aguardar atualização do card usado", "PAUSE", pauseDuration=250)
    spells = next((event for event in events
                   if event["event"]["name"] == "10. Distribuir feitiços disponíveis"), None)
    if spells is None:
        raise ValueError("events.name: esperado '10. Distribuir feitiços disponíveis'")
    spell_condition = spells["conditions"][0]["condition"]
    spell_condition["threshold"] = spell_threshold
    spells["actions"] = []
    action("Selecionar o feitiço reconhecido", "CLICK", event=spells,
           clickPositionType="ON_DETECTED_CONDITION",
           clickOnConditionId=spell_condition["id"], pressDuration=150)
    action("Aguardar seleção do feitiço", "PAUSE", event=spells, pauseDuration=250)
    # A ring keeps every drop away from the center after the calibrated zoom out.
    for index in range(12):
        angle = index * math.tau / 12
        x = round(1170 + 540 * math.cos(angle))
        y = round(510 + 270 * math.sin(angle))
        action(f"Lançar feitiço no anel — posição {index + 1}", "CLICK", event=spells,
               clickPositionType="USER_SELECTED", x=x, y=y, pressDuration=120)
        action("Aguardar lançamento do feitiço", "PAUSE", event=spells, pauseDuration=150)
    # Retry on another frame if the game did not accept every tap. Empty cards
    # fail color matching, so they cannot keep this event running indefinitely.
    # Keep this event enabled: a missing hero must not gate another reference.
    # Used ability cards lose their color and should fail template color matching.
    events.remove(removed_hero)
    for event in events:
        for wrapped_action in event["actions"]:
            toggles = wrapped_action["eventsToggle"]
            wrapped_action["eventsToggle"] = [
                toggle for toggle in toggles if toggle["toggleEventId"] != removed_id
            ]
    for priority, event in enumerate(events):
        event["event"]["priority"] = priority
    used_paths = {
        reference["path"] for event in events for item in event["conditions"]
        for reference in item.get("imageReferences", [])
    }
    return document, {path: assets[path] for path in used_paths}, provenance


def main():
    """Gera o ZIP indicado na CLI; exemplo de execução no docstring do módulo."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("current", type=Path)
    parser.add_argument("historical", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--hero-threshold", type=int, choices=range(MIN_THRESHOLD, MAX_THRESHOLD + 1), default=20,
                        help="Tolerância das referências dos heróis em porcentagem (padrão: 20)")
    parser.add_argument("--spell-threshold", type=int, choices=range(MIN_THRESHOLD, MAX_THRESHOLD + 1), default=20,
                        help="Tolerância das referências dos feitiços em porcentagem (padrão: 20)")
    args = parser.parse_args()
    current, images = read_backup(args.current)
    historical, old_images = read_backup(args.historical)
    updated, assets, _ = update_heroes(
        current, images, historical, old_images,
        hero_threshold=args.hero_threshold, spell_threshold=args.spell_threshold,
    )
    scenario_id = updated["scenario"]["scenario"]["id"]
    with ZipFile(args.output, "w", ZIP_DEFLATED) as archive:
        archive.writestr(f"{scenario_id}/{scenario_id}.json",
                         json.dumps(updated, ensure_ascii=False, indent=2) + "\n")
        for name, content in sorted(assets.items()):
            archive.writestr(f"{scenario_id}/{name}", content)
    print(f"Cenário com seis heróis gerado em {args.output}")


if __name__ == "__main__":
    main()
