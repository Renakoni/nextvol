"""Fetch the versioned embedded SDK bundle used by ordinary APK builds."""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    # Download and extract separately so an interrupted fetch never publishes a partial SDK.
    with tempfile.TemporaryDirectory(prefix="offline-runtime-", dir=output.parent) as temporary:
        staging = Path(temporary)
        archive = staging / "runtime.zip"
        with urllib.request.urlopen(args.url, timeout=120) as response, archive.open("wb") as target:
            shutil.copyfileobj(response, target)
        extracted = staging / "runtime"
        extracted.mkdir()
        with zipfile.ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                path = PurePosixPath(entry.filename)
                if (path.is_absolute() or ".." in path.parts or "\\" in entry.filename
                        or ":" in entry.filename or not path.parts
                        or path.parts[0] not in {"jniLibs", "assets"}):
                    raise ValueError(f"Invalid runtime bundle path: {entry.filename}")
                if entry.is_dir():
                    continue
                target = extracted.joinpath(*path.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(entry) as source, target.open("wb") as destination:
                    shutil.copyfileobj(source, destination)
        manifest = json.loads((extracted / "assets/offline-native/runtime-manifest.json").read_text("utf-8"))
        if manifest["abi"] != "arm64-v8a" or not manifest["libraries"]:
            raise ValueError("Runtime bundle must contain the arm64 SDK libraries")
        for name, details in manifest["libraries"].items():
            if Path(name).name != name or not name.endswith(".so"):
                raise ValueError(f"Invalid runtime library name: {name}")
            library = extracted / "jniLibs/arm64-v8a" / name
            if library.stat().st_size != details["bytes"]:
                raise ValueError(f"Incomplete runtime library: {name}")
        # This is build output owned by this task, never a caller's explicit SDK directory.
        if output.exists():
            shutil.rmtree(output)
        extracted.replace(output)
    print(f"Prepared {len(manifest['libraries'])} embedded SDK libraries in {output}")


if __name__ == "__main__":
    main()
