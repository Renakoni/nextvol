"""Fetch the versioned embedded SDK bundle used by ordinary APK builds."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import urllib.request
import zipfile


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch_archive(url, digest, archive):
    if archive.is_file() and sha256(archive) == digest:
        print("Reusing verified offline SDK archive.", flush=True)
        return
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="offline-download-", dir=archive.parent) as temporary:
        pending = Path(temporary) / "runtime.zip"
        print("Downloading pinned offline SDK archive.", flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, pending.open("wb") as target:
            shutil.copyfileobj(response, target)
        if sha256(pending) != digest:
            raise ValueError("Offline SDK archive SHA-256 mismatch")
        pending.replace(archive)


def main():
    pinned = json.loads(Path(__file__).with_name("runtime.json").read_text("utf-8"))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=pinned["url"])
    parser.add_argument("--sha256", default=pinned["sha256"], type=str.lower)
    parser.add_argument("--archive", type=Path, help="Reusable ZIP; verified before every extraction")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", args.sha256):
        parser.error("--sha256 must be a SHA-256 digest")
    output = args.output.resolve()
    archive = (args.archive or output.with_suffix(".zip")).resolve()
    if archive.is_relative_to(output):
        parser.error("--archive must be outside the extracted output directory")
    fetch_archive(args.url, args.sha256, archive)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Download and extract separately so an interrupted fetch never publishes a partial SDK.
    with tempfile.TemporaryDirectory(prefix="offline-runtime-", dir=output.parent) as temporary:
        staging = Path(temporary)
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
