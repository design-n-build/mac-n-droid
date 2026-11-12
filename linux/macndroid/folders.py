"""Folders travel as one zip of the folder (see PROTOCOL.md, "folder: true")."""
import os
import shutil
import tempfile
import zipfile
from pathlib import Path

SKIP = {".DS_Store", "__MACOSX", "Thumbs.db", "desktop.ini"}


def zip_folder(folder: Path) -> Path:
    """Zips `folder` (the folder itself at the top) into a temporary file and returns its path."""
    out = Path(tempfile.mkdtemp(prefix="macndroid-")) / f"{folder.name}.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as z:
        z.write(folder, folder.name + "/")
        for root, dirs, files in os.walk(folder):
            dirs[:] = sorted(d for d in dirs if d not in SKIP)
            rel = Path(root).relative_to(folder.parent)
            for d in dirs:
                z.write(Path(root) / d, f"{(rel / d).as_posix()}/")
            for f in sorted(files):
                if f in SKIP:
                    continue
                p = Path(root) / f
                if p.is_file():
                    z.write(p, (rel / f).as_posix())
    return out


def _safe_parts(name: str) -> list[str]:
    """Path parts of a zip entry, without anything that could point outside the target folder."""
    parts = [p for p in name.replace("\\", "/").split("/") if p not in ("", ".", "..")]
    if any(p in SKIP for p in parts):
        return []
    return parts


def unpack(zip_path: Path, downloads: Path, name: str) -> Path | None:
    """Unpacks a received folder zip into Downloads and returns the folder (None if it isn't a valid zip)."""
    work = Path(tempfile.mkdtemp(prefix=".macndroid-", dir=downloads))
    try:
        with zipfile.ZipFile(zip_path) as z:
            for info in z.infolist():
                parts = _safe_parts(info.filename)
                if not parts:
                    continue
                target = work.joinpath(*parts)
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst, 1 << 20)
    except (zipfile.BadZipFile, OSError, RuntimeError):
        shutil.rmtree(work, ignore_errors=True)
        return None
    finally:
        zip_path.unlink(missing_ok=True)
    items = [p for p in work.iterdir()]
    base = name[:-4] if name.lower().endswith(".zip") else name
    if len(items) == 1 and items[0].is_dir():
        src = items[0]
        base = src.name
    else:
        src = work
    dest = downloads / base
    n = 2
    while dest.exists():
        dest = downloads / f"{base} {n}"
        n += 1
    shutil.move(str(src), str(dest))
    shutil.rmtree(work, ignore_errors=True)
    return dest
