"""Package an extracted vendor SDK and its metadata for loading outside the APK."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def package(source: Path, output: Path):
    manifest_path = source / "assets/offline-native/runtime-manifest.json"
    manifest = json.loads(manifest_path.read_bytes())
    library = source / "jniLibs/arm64-v8a/libissavth.so"
    data = library.read_bytes()
    # The classic IssTTS dependency declares the newer SDK's SONAME. The two libraries
    # have different exports. Match DT_NEEDED by changing only its equal-length .dynstr entry.
    original, corrected = b"\0libissauth.so\0", b"\0libissavth.so\0"
    if data.count(original) != 1 or corrected in data:
        raise ValueError(f"Unexpected SONAME in {library.name}")
    data = data.replace(original, corrected)
    manifest["libraries"][library.name]["sha256"] = hashlib.sha256(data).hexdigest()
    overrides = {library: data, manifest_path: (json.dumps(manifest, indent=2) + "\n").encode()}

    names = sorted(p.relative_to(source).as_posix() for tree in ("assets", "jniLibs")
                   for p in (source / tree).rglob("*") if p.is_file())
    with ZipFile(output, "x") as archive:
        for name in names:
            path = source / name
            entry = ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, overrides[path] if path in overrides else path.read_bytes(),
                             compress_type=ZIP_DEFLATED, compresslevel=9)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    package(args.source, args.output)
