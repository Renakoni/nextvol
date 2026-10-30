"""Build the vendored Chinese Matcha backend and its source-built #793 runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources():
    expected = {}
    for line in (ROOT / 'sources.sha256').read_text(encoding='utf-8').splitlines():
        checksum, name = line.split('  ', 1)
        expected[name] = checksum
    actual = {p.relative_to(ROOT).as_posix(): digest(p) for p in (ROOT / 'vendor').rglob('*') if p.is_file()}
    actual.update({p.relative_to(ROOT).as_posix(): digest(p) for p in (ROOT / 'patches').glob('*') if p.is_file()})
    if actual != expected:
        raise ValueError('Matcha source inventory mismatch')


def run(args):
    print('+ ' + subprocess.list2cmdline([str(arg) for arg in args]), flush=True)
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--output', type=Path, default=ROOT / 'build')
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    output = args.output.resolve()
    identity = output / 'assets/matcha-native/build-manifest.json'
    identity.unlink(missing_ok=True)
    verify_sources()
    ndk = args.ndk.resolve()
    runtime = output / 'onnxruntime'
    run([sys.executable, ROOT.parent / 'onnxruntime/build_android.py', '--ndk', ndk,
         '--cmake', args.cmake, '--ninja', args.ninja, '--output', runtime, '--jobs', args.jobs])
    build = output / 'matcha-arm64'
    run([args.cmake, '-S', ROOT, '-B', build, '-G', 'Ninja',
         '-DCMAKE_MAKE_PROGRAM=' + args.ninja,
         '-DCMAKE_TOOLCHAIN_FILE=' + (ndk / 'build/cmake/android.toolchain.cmake').as_posix(),
         '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
         '-DCMAKE_BUILD_TYPE=Release', '-DORT_INSTALL=' + (runtime / 'install').as_posix()])
    run([args.cmake, '--build', build, '--parallel', args.jobs])
    verify_sources()
    llvm = next((ndk / 'toolchains/llvm/prebuilt').iterdir()) / 'bin'
    readelf = llvm / ('llvm-readelf.exe' if os.name == 'nt' else 'llvm-readelf')
    library = build / 'libnextvol-matcha.so'
    elf = subprocess.check_output([readelf, '-h', '-l', '-d', library], text=True)
    loads = [line.split() for line in elf.splitlines() if line.strip().startswith('LOAD ')]
    needed = re.findall(r'Shared library: \[(.*?)\]', elf)
    if ('AArch64' not in elf or not loads or re.search(r'\((?:RUNPATH|RPATH)\)', elf) or
        set(needed) - {'libonnxruntime.so', 'libandroid.so', 'liblog.so', 'libm.so', 'libdl.so', 'libc.so'} or
        'libonnxruntime.so' not in needed or
        any(int(row[-1], 16) < 16384 or (int(row[1], 16) - int(row[2], 16)) % 16384 for row in loads)):
        raise ValueError('Unexpected Matcha ELF profile')
    jni = output / 'jniLibs/arm64-v8a'
    jni.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(library, jni / library.name)
    shutil.copyfile(runtime / 'install/lib/libonnxruntime.so', jni / 'libonnxruntime.so')
    assets = identity.parent
    assets.mkdir(parents=True, exist_ok=True)
    for source in json.loads((ROOT / 'sources.json').read_text(encoding='utf-8')):
        for name in source['licenses']:
            target = assets / 'licenses' / Path(name).relative_to('vendor')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
    shutil.copytree(runtime / 'install/licenses', assets / 'licenses/onnxruntime', dirs_exist_ok=True)
    manifest = {
        'sherpa_version': '1.12.10', 'sherpa_commit': '6b1fbdedd2d90dba2714b7aff825412d89e45eba',
        'profile': 'Chinese Matcha / HiFi-GAN / CPU / two threads / one instance',
        'abi': 'arm64-v8a', 'android_api': 26, 'stl': 'c++_static',
        'source_inventory_sha256': digest(ROOT / 'sources.sha256'),
        'recipe_sha256': {name: digest(ROOT / name) for name in ('build_android.py', 'CMakeLists.txt', 'sources.cmake', 'sources.json', 'jni.cc')},
        'library_sha256': digest(library), 'library_bytes': library.stat().st_size,
        'needed_libraries': needed, 'load_segment_alignments': [row[-1] for row in loads],
        'runtime': json.loads((runtime / 'install/build-manifest.json').read_text(encoding='utf-8')),
    }
    identity.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    (output / 'matcha-elf.txt').write_text(elf, encoding='utf-8')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
