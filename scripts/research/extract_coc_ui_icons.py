#!/usr/bin/env python3
"""Extract Clash of Clans troop and spell icons from a decoded ui.sc atlas."""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import shutil
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

TABLE_NAMES = ("characters", "heroes", "pets", "spells")
DEFAULT_RESEARCH_ROOT = Path(".local-research/clash-of-clans/18.400.9")
TRUTHY_VALUES = {"1", "true", "yes"}


@dataclass
class MovieNode:
    children: list[int] = field(default_factory=list)
    frame_counts: list[int] = field(default_factory=list)
    elements: list[int] = field(default_factory=list)


@dataclass
class UiGraph:
    exports: dict[str, int] = field(default_factory=dict)
    movies: dict[int, MovieNode] = field(default_factory=dict)
    shape_ids: set[int] = field(default_factory=set)


@dataclass(frozen=True)
class AtlasRegion:
    shape_id: int
    command_index: int
    texture_index: int
    min_u: float
    min_v: float
    max_u: float
    max_v: float
    orientation: str
    points: tuple[tuple[float, float, float, float], ...]


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-root", type=Path, default=DEFAULT_RESEARCH_ROOT)
    parser.add_argument("--atlas-root", type=Path)
    parser.add_argument("--cell-size", type=int, default=196)
    return parser.parse_args()


def _read_csv_rows(csv_path: Path) -> list[dict[str, str]]:
    with csv_path.open(encoding="utf-8-sig", newline="") as csv_file:
        return list(csv.DictReader(csv_file))


def _load_portuguese_names(localization_path: Path) -> dict[str, str]:
    names = {}
    for row in _read_csv_rows(localization_path):
        if row.get("TID") not in {"", "string", None}:
            names[row["TID"]] = row.get("PT", "")
    return names


def _is_true(value: str | None) -> bool:
    return (value or "").strip().lower() in TRUTHY_VALUES


def _classify_character(row: dict[str, str]) -> str:
    if _is_true(row.get("EnabledByCalendar")):
        return "event-or-seasonal"
    if _is_true(row.get("EnabledBySuperLicence")):
        return "super-troops"
    if row.get("ProductionBuilding") == "Siege Workshop":
        return "siege-machines"
    if row.get("ProductionBuilding") == "BB Barracks" or row.get("VillageType") == "1":
        return "builder-base"
    if _is_true(row.get("DefensiveTroop")):
        return "defensive"
    if _is_true(row.get("IsSecondaryTroop")):
        return "secondary-or-summoned"
    if row.get("ProductionBuilding") in {"Barracks", "Dark Barracks"}:
        return "main-village"
    return "other-variants"


def _classify_spell(row: dict[str, str]) -> str:
    if _is_true(row.get("EnabledByCalendar")):
        return "event-or-seasonal"
    if row.get("VillageType") == "1":
        return "builder-base"
    if row.get("ProductionBuilding") == "Dark Spell Factory":
        return "dark-spells"
    if row.get("ProductionBuilding") == "Spell Factory":
        return "elixir-spells"
    return "other-variants"


def _classify_alias(table_name: str, row: dict[str, str]) -> str:
    if table_name == "characters":
        return _classify_character(row)
    if table_name == "spells":
        return _classify_spell(row)
    if table_name == "heroes":
        return "builder-base" if row.get("VillageType") == "1" else "main-village"
    if _is_true(row.get("IsSecondaryTroop")):
        return "secondary-or-summoned"
    return "pets"


def _build_alias(table_name: str, row: dict[str, str], names: dict[str, str]) -> dict:
    tid = row.get("TID", "")
    return {
        "sourceTable": table_name,
        "internalName": row.get("Name", ""),
        "namePT": names.get(tid, ""),
        "tid": tid,
        "category": _classify_alias(table_name, row),
        "deprecated": _is_true(row.get("Deprecated")),
        "enabledByCalendar": _is_true(row.get("EnabledByCalendar")),
        "villageType": row.get("VillageType", ""),
        "productionBuilding": row.get("ProductionBuilding", ""),
    }


def _load_icon_aliases(tables_root: Path) -> dict[str, list[dict]]:
    names = _load_portuguese_names(tables_root / "localization" / "pt.csv")
    aliases: dict[str, list[dict]] = defaultdict(list)
    for table_name in TABLE_NAMES:
        for row in _read_csv_rows(tables_root / "logic" / f"{table_name}.csv"):
            export_name = row.get("IconExportName", "")
            if export_name in {"", "String"}:
                continue
            aliases[export_name].append(_build_alias(table_name, row, names))
    return dict(sorted(aliases.items()))


def _read_export(parts: list[str], graph: UiGraph) -> None:
    graph.exports[parts[2]] = int(parts[1])


def _read_movie(parts: list[str], graph: UiGraph) -> None:
    graph.movies[int(parts[1])] = MovieNode()


def _read_child(parts: list[str], graph: UiGraph) -> None:
    graph.movies[int(parts[1])].children.append(int(parts[3]))


def _read_frame(parts: list[str], graph: UiGraph) -> None:
    graph.movies[int(parts[1])].frame_counts.append(int(parts[3]))


def _read_element(parts: list[str], graph: UiGraph) -> None:
    graph.movies[int(parts[1])].elements.append(int(parts[3]))


def _load_ui_graph(objects_path: Path) -> UiGraph:
    graph = UiGraph()
    readers = {
        "EXPORT": _read_export,
        "MOVIE": _read_movie,
        "CHILD": _read_child,
        "FRAME": _read_frame,
        "ELEMENT": _read_element,
    }
    with objects_path.open(encoding="utf-8") as object_file:
        for line in object_file:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "VERTEX":
                graph.shape_ids.add(int(parts[1]))
            elif parts[0] in readers:
                readers[parts[0]](parts, graph)
    return graph


def _first_frame_children(movie: MovieNode) -> list[int]:
    element_count = movie.frame_counts[0] if movie.frame_counts else len(movie.elements)
    child_ids = []
    for instance_index in movie.elements[:element_count]:
        if instance_index < len(movie.children):
            child_ids.append(movie.children[instance_index])
    return child_ids


def _find_reachable_shapes(root_id: int, graph: UiGraph) -> set[int]:
    pending = [root_id]
    visited = set()
    shapes = set()
    while pending:
        object_id = pending.pop()
        if object_id in visited:
            continue
        visited.add(object_id)
        if object_id in graph.shape_ids:
            shapes.add(object_id)
        elif object_id in graph.movies:
            pending.extend(_first_frame_children(graph.movies[object_id]))
    return shapes


def _target_shape_map(aliases: dict[str, list[dict]], graph: UiGraph) -> dict[str, set[int]]:
    targets = {}
    for export_name in aliases:
        export_id = graph.exports.get(export_name)
        targets[export_name] = _find_reachable_shapes(export_id, graph) if export_id else set()
    return targets


def _collect_region_points(
    objects_path: Path,
    target_shape_ids: set[int],
) -> dict[tuple[int, int, int], list[tuple[float, float, float, float]]]:
    points: dict[tuple[int, int, int], list[tuple[float, float, float, float]]] = defaultdict(list)
    with objects_path.open(encoding="utf-8") as object_file:
        for line in object_file:
            if not line.startswith("VERTEX\t"):
                continue
            parts = line.rstrip("\n").split("\t")
            shape_id = int(parts[1])
            if shape_id not in target_shape_ids:
                continue
            key = (shape_id, int(parts[2]), int(parts[3]))
            points[key].append(tuple(float(value) for value in parts[4:8]))
    return points


def _point_bounds(
    points: list[tuple[float, float, float, float]],
) -> tuple[float, float, float, float]:
    u_values = [point[2] for point in points]
    v_values = [point[3] for point in points]
    return min(u_values), min(v_values), max(u_values), max(v_values)


def _normalized_vertices(
    points: list[tuple[float, float, float, float]],
) -> list[tuple[float, float, float, float]]:
    x_values, y_values = [p[0] for p in points], [p[1] for p in points]
    min_u, min_v, max_u, max_v = _point_bounds(points)
    x_span, y_span = max(x_values) - min(x_values), max(y_values) - min(y_values)
    u_span, v_span = max_u - min_u, max_v - min_v
    return [
        ((x - min(x_values)) / x_span, (y - min(y_values)) / y_span,
         (u - min_u) / u_span, (v - min_v) / v_span)
        for x, y, u, v in points
    ]


def _oriented_uv(u_value: float, v_value: float, orientation: str) -> tuple[float, float]:
    transforms = {
        "identity": (u_value, v_value),
        "flip-left-right": (1 - u_value, v_value),
        "flip-top-bottom": (u_value, 1 - v_value),
        "rotate-180": (1 - u_value, 1 - v_value),
        "rotate-90": (v_value, 1 - u_value),
        "rotate-270": (1 - v_value, u_value),
        "transpose": (v_value, u_value),
        "transverse": (1 - v_value, 1 - u_value),
    }
    return transforms[orientation]


def _orientation_error(
    vertices: list[tuple[float, float, float, float]],
    orientation: str,
) -> float:
    errors = []
    for x_value, y_value, u_value, v_value in vertices:
        mapped_x, mapped_y = _oriented_uv(u_value, v_value, orientation)
        errors.append((x_value - mapped_x) ** 2 + (y_value - mapped_y) ** 2)
    return sum(errors) / len(errors)


def _detect_orientation(points: list[tuple[float, float, float, float]]) -> str:
    vertices = _normalized_vertices(points)
    orientations = (
        "identity", "flip-left-right", "flip-top-bottom", "rotate-180",
        "rotate-90", "rotate-270", "transpose", "transverse",
    )
    return min(orientations, key=lambda value: _orientation_error(vertices, value))


def _regions_by_shape(
    points_by_region: dict[tuple[int, int, int], list[tuple[float, float, float, float]]],
) -> dict[int, list[AtlasRegion]]:
    regions: dict[int, list[AtlasRegion]] = defaultdict(list)
    for (shape_id, command_index, texture_index), points in points_by_region.items():
        bounds = _point_bounds(points)
        regions[shape_id].append(
            AtlasRegion(
                shape_id,
                command_index,
                texture_index,
                *bounds,
                _detect_orientation(points),
                tuple(points),
            )
        )
    return regions


def _region_pixel_area(region: AtlasRegion, atlases: list[Image.Image]) -> float:
    if region.texture_index >= len(atlases):
        return -1
    width, height = atlases[region.texture_index].size
    return (region.max_u - region.min_u) * width * (region.max_v - region.min_v) * height


def _choose_region(
    shape_ids: set[int],
    regions: dict[int, list[AtlasRegion]],
    atlases: list[Image.Image],
) -> tuple[AtlasRegion | None, list[AtlasRegion]]:
    candidates = [region for shape_id in shape_ids for region in regions.get(shape_id, [])]
    valid = [region for region in candidates if _region_pixel_area(region, atlases) > 0]
    chosen = max(valid, key=lambda region: _region_pixel_area(region, atlases), default=None)
    return chosen, candidates


def _crop_box(region: AtlasRegion, atlas: Image.Image) -> tuple[int, int, int, int]:
    width, height = atlas.size
    left = max(0, math.floor(region.min_u * width))
    top = max(0, math.floor(region.min_v * height))
    right = min(width, math.ceil(region.max_u * width))
    bottom = min(height, math.ceil(region.max_v * height))
    return left, top, right, bottom


def _safe_filename(export_name: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", export_name).strip("_") + ".png"


def _region_json(region: AtlasRegion) -> dict:
    return {
        "shapeId": region.shape_id,
        "commandIndex": region.command_index,
        "textureIndex": region.texture_index,
        "uv": [region.min_u, region.min_v, region.max_u, region.max_v],
        "orientation": region.orientation,
        "vertexCount": len(region.points),
    }


def _region_mask(
    region: AtlasRegion,
    atlas: Image.Image,
    crop_box: tuple[int, int, int, int],
) -> Image.Image:
    left, top, right, bottom = crop_box
    mask = Image.new("L", (right - left, bottom - top), 0)
    pixel_vertices = [
        (u_value * atlas.width - left, v_value * atlas.height - top)
        for _, _, u_value, v_value in region.points
    ]
    mask_draw = ImageDraw.Draw(mask)
    for index in range(len(pixel_vertices) - 2):
        mask_draw.polygon(pixel_vertices[index:index + 3], fill=255)
    return mask


def _mask_unused_atlas_pixels(
    image: Image.Image,
    region: AtlasRegion,
    atlas: Image.Image,
    crop_box: tuple[int, int, int, int],
) -> Image.Image:
    mesh_mask = _region_mask(region, atlas, crop_box)
    image.putalpha(ImageChops.multiply(image.getchannel("A"), mesh_mask))
    return image


def _orient_icon(image: Image.Image, orientation: str) -> Image.Image:
    transpose = {
        "identity": None,
        "flip-left-right": Image.Transpose.FLIP_LEFT_RIGHT,
        "flip-top-bottom": Image.Transpose.FLIP_TOP_BOTTOM,
        "rotate-180": Image.Transpose.ROTATE_180,
        "rotate-90": Image.Transpose.ROTATE_90,
        "rotate-270": Image.Transpose.ROTATE_270,
        "transpose": Image.Transpose.TRANSPOSE,
        "transverse": Image.Transpose.TRANSVERSE,
    }[orientation]
    return image if transpose is None else image.transpose(transpose)


def _extract_icon(
    export_name: str,
    shape_ids: set[int],
    regions: dict[int, list[AtlasRegion]],
    atlases: list[Image.Image],
    output_root: Path,
) -> dict:
    chosen, candidates = _choose_region(shape_ids, regions, atlases)
    result = {"status": "missing-region", "reachableShapeIds": sorted(shape_ids)}
    result["candidates"] = [_region_json(region) for region in candidates]
    if chosen is None:
        return result
    filename = _safe_filename(export_name)
    atlas = atlases[chosen.texture_index]
    box = _crop_box(chosen, atlas)
    image = atlas.crop(box)
    image = _mask_unused_atlas_pixels(image, chosen, atlas, box)
    image = _orient_icon(image, chosen.orientation)
    image.save(output_root / "by-export" / filename)
    result.update({"status": "extracted", "file": f"by-export/{filename}"})
    result.update({"size": list(image.size), "cropBox": list(box), "region": _region_json(chosen)})
    return result


def _record_groups(aliases: list[dict]) -> set[tuple[str, str]]:
    groups = set()
    for alias in aliases:
        source = alias["sourceTable"]
        kind = "troops" if source == "characters" else source
        groups.add((kind, alias["category"]))
    return groups


def _copy_grouped_icons(output_root: Path, records: dict[str, dict]) -> None:
    for export_name, record in records.items():
        if record["extraction"]["status"] != "extracted":
            continue
        source = output_root / record["extraction"]["file"]
        for kind, category in _record_groups(record["aliases"]):
            destination = output_root / kind / category / source.name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)


def _has_icon_kind(aliases: list[dict], kind: str) -> bool:
    table_name = "characters" if kind == "troops" else kind
    return any(alias["sourceTable"] == table_name for alias in aliases)


def _load_sheet_font() -> ImageFont.ImageFont:
    font_path = Path("/usr/share/fonts/TTF/DejaVuSans.ttf")
    return ImageFont.truetype(font_path, 13) if font_path.exists() else ImageFont.load_default()


def _fit_thumbnail(icon: Image.Image, cell_size: int) -> Image.Image:
    thumbnail = icon.copy()
    thumbnail.thumbnail((cell_size - 20, cell_size - 48), Image.Resampling.LANCZOS)
    return thumbnail


def _create_contact_sheet(
    title: str,
    exports: list[str],
    output_root: Path,
    records: dict[str, dict],
    cell_size: int,
) -> None:
    if not exports:
        return
    columns = 6
    rows = math.ceil(len(exports) / columns)
    sheet = Image.new("RGBA", (columns * cell_size, rows * cell_size + 42), "#20242b")
    draw = ImageDraw.Draw(sheet)
    font = _load_sheet_font()
    draw.text((14, 12), f"{title} — {len(exports)} ícones", fill="white", font=font)
    for index, export_name in enumerate(exports):
        _draw_sheet_cell(sheet, draw, font, index, export_name, output_root, records, cell_size)
    sheet.save(output_root / f"{title}.png")


def _draw_sheet_cell(
    sheet: Image.Image,
    draw: ImageDraw.ImageDraw,
    font: ImageFont.ImageFont,
    index: int,
    export_name: str,
    output_root: Path,
    records: dict[str, dict],
    cell_size: int,
) -> None:
    x_pos = (index % 6) * cell_size
    y_pos = (index // 6) * cell_size + 42
    draw.rectangle((x_pos, y_pos, x_pos + cell_size - 2, y_pos + cell_size - 2), fill="#303640")
    icon = Image.open(output_root / records[export_name]["extraction"]["file"]).convert("RGBA")
    thumbnail = _fit_thumbnail(icon, cell_size)
    icon_x = x_pos + (cell_size - thumbnail.width) // 2
    sheet.alpha_composite(thumbnail, (icon_x, y_pos + 8))
    label = export_name.removeprefix("icon_")[:24]
    draw.text((x_pos + 8, y_pos + cell_size - 30), label, fill="white", font=font)


def _write_contact_sheets(
    output_root: Path,
    records: dict[str, dict],
    cell_size: int,
) -> None:
    extracted = {
        name for name, record in records.items() if record["extraction"]["status"] == "extracted"
    }
    for kind in ("troops", "heroes", "pets", "spells"):
        exports = sorted(
            name for name in extracted if _has_icon_kind(records[name]["aliases"], kind)
        )
        _create_contact_sheet(kind, exports, output_root, records, cell_size)
    _create_contact_sheet("all-icons", sorted(extracted), output_root, records, cell_size)


def _write_manifest(output_root: Path, records: dict[str, dict]) -> None:
    extracted_count = sum(
        record["extraction"]["status"] == "extracted" for record in records.values()
    )
    manifest = {
        "source": {"package": "com.supercell.clashofclans", "version": "18.400.9"},
        "summary": {
            "uniqueIconExports": len(records),
            "extracted": extracted_count,
            "missing": len(records) - extracted_count,
        },
        "icons": records,
    }
    manifest_path = output_root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


def _prepare_output(output_root: Path) -> None:
    (output_root / "by-export").mkdir(parents=True, exist_ok=True)


def _load_atlases(atlas_root: Path) -> list[Image.Image]:
    atlas_paths = sorted(atlas_root.glob("ui_*.png"))
    if not atlas_paths:
        raise ValueError(f"No ui_*.png atlas found in {atlas_root}; expected decoded PNG files")
    return [Image.open(path).convert("RGBA") for path in atlas_paths]


def _build_records(
    aliases: dict[str, list[dict]],
    target_shapes: dict[str, set[int]],
    regions: dict[int, list[AtlasRegion]],
    atlases: list[Image.Image],
    output_root: Path,
) -> dict[str, dict]:
    records = {}
    for export_name, export_aliases in aliases.items():
        extraction = _extract_icon(
            export_name, target_shapes[export_name], regions, atlases, output_root
        )
        records[export_name] = {"aliases": export_aliases, "extraction": extraction}
    return records


def main() -> None:
    """Extract and catalog every icon variant referenced by game tables.

    Example: ``python scripts/research/extract_coc_ui_icons.py``
    """
    args = _parse_args()
    decoded_root = args.research_root / "decoded"
    objects_path = decoded_root / "ui" / "ui-objects.tsv"
    output_root = args.research_root / "icons"
    aliases = _load_icon_aliases(decoded_root / "tables")
    graph = _load_ui_graph(objects_path)
    target_shapes = _target_shape_map(aliases, graph)
    target_shape_ids = set().union(*target_shapes.values())
    points_by_region = _collect_region_points(objects_path, target_shape_ids)
    regions = _regions_by_shape(points_by_region)
    atlas_root = args.atlas_root or decoded_root / "ui-render"
    atlases = _load_atlases(atlas_root)
    _prepare_output(output_root)
    records = _build_records(aliases, target_shapes, regions, atlases, output_root)
    _copy_grouped_icons(output_root, records)
    _write_contact_sheets(output_root, records, args.cell_size)
    _write_manifest(output_root, records)
    print(json.dumps(json.loads((output_root / "manifest.json").read_text())["summary"]))


if __name__ == "__main__":
    main()
