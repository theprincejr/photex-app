"""Writes a byte-stable zip of a directory tree."""

import pathlib
import sys
import zipfile

FIXED_DATE_TIME = (1980, 1, 1, 0, 0, 0)
FILE_MODE = 0o100644


def make_zip(out_path: pathlib.Path, root: pathlib.Path) -> int:
    if not root.is_dir():
        raise SystemExit(f"error: {root} is not a directory")
    files = sorted(
        (path for path in root.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(root).as_posix(),
    )
    if not files:
        raise SystemExit(f"error: {root} holds no files")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_path, "w") as archive:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(root).as_posix(), date_time=FIXED_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = FILE_MODE << 16
            info.extra = b""
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return len(files)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: make-zip.py <out.zip> <root dir>")
    count = make_zip(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
    print(f"{sys.argv[1]}: {count} files")


if __name__ == "__main__":
    main()
