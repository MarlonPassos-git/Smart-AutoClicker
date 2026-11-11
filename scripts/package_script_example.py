"""Empacota código e recursos locais sem executar JavaScript.

Exemplo: python scripts/package_script_example.py examples/scripts/generic-recovery build/script-examples/generic-recovery.zip
"""

import argparse
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def package_example(source: Path, destination: Path) -> int:
    """Cria um ZIP portátil e determinístico em um destino novo, fora da pasta de origem."""
    source = source.resolve()
    destination = destination.resolve()
    if not source.is_dir():
        raise ValueError(f"source={source}; esperado um diretório de pacote existente")
    if destination == source or source in destination.parents:
        raise ValueError(f"destination={destination}; esperado um arquivo fora do diretório de origem")
    if destination.exists():
        raise ValueError(f"destination={destination}; esperado um arquivo novo, sem substituir o anterior")
    manifest_path = source / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"source={source}; esperado manifest.json na raiz")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError(f"manifest.json.type={type(manifest).__name__}; esperado um objeto JSON")
    entry = manifest.get("entry")
    if (
        not isinstance(entry, str)
        or not entry.endswith(".js")
        or len(entry) > 200
        or any(part in ("", ".", "..") for part in entry.split("/"))
        or any(char in entry for char in ("\\", ":", "\0"))
    ):
        raise ValueError(f"manifest.entry={entry!r}; esperado caminho relativo portátil de um arquivo .js dentro do pacote")
    if not (source / entry).is_file():
        raise ValueError(f"manifest.entry={entry!r}; esperado um arquivo JavaScript existente dentro do pacote")
    files = sorted(path for path in source.rglob("*") if path.is_file())
    if any(path.is_symlink() for path in source.rglob("*")):
        raise ValueError(f"source={source}; esperados arquivos regulares, sem links simbólicos")
    destination.parent.mkdir(parents=True, exist_ok=True)
    created = False
    try:
        with destination.open("xb") as stream:
            created = True
            with ZipFile(stream, "w", compression=ZIP_DEFLATED) as archive:
                for path in files:
                    relative = path.relative_to(source).as_posix()
                    info = ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
                    info.compress_type = ZIP_DEFLATED
                    info.external_attr = 0o644 << 16
                    archive.writestr(info, path.read_bytes())
    except Exception:
        if created:
            destination.unlink(missing_ok=True)
        raise
    return len(files)


def main() -> None:
    """Lê origem/destino e informa o resultado em texto simples para o terminal."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        count = package_example(args.source, args.destination)
    except (OSError, ValueError) as failure:
        parser.error(str(failure))
    print(f"Pacote criado: {args.destination} ({count} arquivos)")


if __name__ == "__main__":
    main()
