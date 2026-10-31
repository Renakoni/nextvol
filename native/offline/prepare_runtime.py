"""Prepare locally supplied embedded SDK libraries without copying an application's Java code."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


# Include explicit Java/dlopen entry libraries; DT_NEEDED only adds their ELF dependencies.
ENGINE_LIBRARIES = {
    "acapela": ["acattsandroid", "ttsacattsandroidextended"],
    "bdetts": ["bd_etts", "bd_ettm", "bd_ettf"],
    "cereproc": ["cerevoice_eng"],
    "google": ["google_speech_sbg_tts_jni"],
    "isstts": ["issxtts30", "issxtts40"],
    "larktts": ["synthesizer", "Aisound"],
    "microsoft": ["Microsoft.CognitiveServices.Speech.java.bindings", "Microsoft.CognitiveServices.Speech.core",
        "Microsoft.CognitiveServices.Speech.extension.telemetry",
        "Microsoft.CognitiveServices.Speech.extension.embedded.tts", "speexdsp"],
    "msctts": ["msc"],
    "samsung": ["smtloader"],
    "sougou": ["omp", "evalite_uapi", "sgsplit", "sgtts", "sgproxy_lib", "sgtts_lib"],
    "vcstts": ["bl_vcs"],
    "vocalizer": ["NuanceVocalizer"],
    "weread": ["weread-tts", "hwTTS"],
    "xiaomi": ["tts-offline"],
}
PLATFORM_LIBRARIES = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libstdc++.so", "libz.so",
    "libandroid.so", "libaaudio.so", "libEGL.so", "libGLESv2.so", "libnativewindow.so", "libsync.so",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Caller-supplied SDK runtime carrier")
    parser.add_argument("--microsoft-aar", type=Path, required=True, help="The official 1.45.0 client SDK AAR")
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="A new directory outside the source tree")
    args = parser.parse_args()
    output = args.output.resolve()
    repository = Path(__file__).resolve().parents[2]
    if output.is_relative_to(repository) or output.exists():
        raise ValueError("Use a new output directory outside the repository")
    llvm = next((args.ndk / "toolchains/llvm/prebuilt").iterdir()) / "bin"
    readelf = llvm / ("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
    abi = "arm64-v8a"
    libraries = output / "jniLibs" / abi
    libraries.mkdir(parents=True)
    assets = output / "assets/offline-native"
    assets.mkdir(parents=True)
    with zipfile.ZipFile(args.apk) as apk, zipfile.ZipFile(args.microsoft_aar) as aar:
        supplied = {Path(name).name: name for name in apk.namelist() if name.startswith(f"lib/{abi}/") and name.endswith(".so")}
        official = {Path(name).name for name in aar.namelist() if name.startswith(f"jni/{abi}/") and name.endswith(".so")}
        pending = {f"lib{name}.so" for names in ENGINE_LIBRARIES.values() for name in names}
        optional = "libbd_ettl.so"
        missing_optional = []
        if optional in supplied:
            pending.add(optional)
        else:
            missing_optional.append({"library": optional, "engine": "bdetts", "variant": "appId 37111357"})
        inventory = {}
        while pending:
            name = sorted(pending)[0]
            pending.remove(name)
            if name in inventory or name in official:
                continue
            if name not in supplied:
                raise ValueError(f"The supplied runtime is missing {name}")
            target = libraries / name
            target.write_bytes(apk.read(supplied[name]))
            elf = subprocess.check_output([str(readelf), "-h", "-l", "-d", str(target)], text=True)
            if "AArch64" not in elf:
                raise ValueError(f"Unexpected SDK architecture: {name}")
            needed = re.findall(r"Shared library: \[(.*?)\]", elf)
            loads = [line.split() for line in elf.splitlines() if line.strip().startswith("LOAD ")]
            if not loads:
                raise ValueError(f"SDK has no loadable segments: {name}")
            inventory[name] = {
                "bytes": target.stat().st_size,
                "needed": needed,
                "minimum_segment_alignment": min(int(row[-1], 16) for row in loads),
                "supports_16k_pages": all(int(row[-1], 16) >= 16384 and
                    (int(row[1], 16) - int(row[2], 16)) % 16384 == 0 for row in loads),
                "requires_api_26": any(dependency in {"libaaudio.so", "libnativewindow.so"} for dependency in needed),
            }
            for dependency in needed:
                if dependency in PLATFORM_LIBRARIES or dependency in official:
                    continue
                if dependency not in supplied:
                    raise ValueError(f"Missing SDK companion library: {name} -> {dependency}")
                pending.add(dependency)
    manifest = {
        "abi": abi,
        "microsoft_client_sdk": "1.45.0",
        "engines": ENGINE_LIBRARIES,
        "libraries": inventory,
        "provided_by_microsoft_aar": sorted(official),
        "missing_optional": missing_optional,
        "notes": ["SDK authorization is unchanged.", "Voice models and standalone application license files are not packaged by this tool.",
            "Native ABI, Android API and memory-page compatibility still apply at runtime."],
    }
    (assets / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "libraries": len(inventory),
        "bytes": sum(item["bytes"] for item in inventory.values()), "missing_optional": missing_optional,
        "libraries_requiring_4k_pages": [name for name, item in inventory.items() if not item["supports_16k_pages"]]}, indent=2))


if __name__ == "__main__":
    main()
