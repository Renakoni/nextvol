"""Build host protoc and Android arm64 ONNX Runtime entirely from vendored sources."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from verify_sources import ROOT, verify_sources


NDK_VERSION = '28.2.13676358'


def run(args):
    print('+ ' + subprocess.list2cmdline([str(arg) for arg in args]), flush=True)
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--output', type=Path, default=ROOT / 'build')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    output = args.output.resolve()
    host = output / 'host-protobuf'
    android = output / 'android-arm64'
    install = output / 'install'
    # A failed rebuild must not leave an old success manifest behind.
    identity = install / 'build-manifest.json'
    identity.unlink(missing_ok=True)
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    ndk = args.ndk.resolve()
    if not re.search(r'Pkg\.Revision\s*=\s*' + re.escape(NDK_VERSION) + r'\s*$',
                     (ndk / 'source.properties').read_text(), re.MULTILINE):
        parser.error(f'Use Android NDK {NDK_VERSION}')
    cmake_version = subprocess.check_output([args.cmake, '--version'], text=True).splitlines()[0]
    version = re.search(r'(\d+)\.(\d+)\.(\d+)', cmake_version)
    if not version or not ((3, 26) <= tuple(map(int, version.groups()[:2])) < (4, 0)):
        parser.error('Use CMake >= 3.26 and < 4.0')
    print(f'Verified {verify_sources()} source files.', flush=True)
    host_options = ['-Dprotobuf_BUILD_TESTS=OFF', '-Dprotobuf_WITH_ZLIB=OFF',
                    '-Dprotobuf_BUILD_SHARED_LIBS=OFF', '-Dprotobuf_BUILD_PROTOC_BINARIES=ON',
                    '-DCMAKE_BUILD_TYPE=Release', '-DFETCHCONTENT_FULLY_DISCONNECTED=ON']
    if os.name == 'nt':
        host_options += ['-G', 'Visual Studio 17 2022', '-A', 'x64']
        protoc = host / 'Release/protoc.exe'
    else:
        host_options += ['-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + args.ninja]
        protoc = host / 'protoc'
    run([args.cmake, '-S', ROOT / 'vendor/protobuf', '-B', host, *host_options])
    run([args.cmake, '--build', host, '--config', 'Release', '--target', 'protoc',
         '--parallel', args.jobs])
    run([protoc, '--version'])
    run([args.cmake, '-S', ROOT / 'vendor/onnxruntime/cmake', '-B', android, '-G', 'Ninja',
         '-C', ROOT / 'cmake/android-arm64.cmake',
         '-DCMAKE_MAKE_PROGRAM=' + args.ninja,
         '-DCMAKE_TOOLCHAIN_FILE=' + (ndk / 'build/cmake/android.toolchain.cmake').as_posix(),
         '-DONNX_CUSTOM_PROTOC_EXECUTABLE=' + protoc.as_posix(),
         '-DPython_EXECUTABLE=' + Path(sys.executable).as_posix()])
    run([args.cmake, '--build', android, '--target', 'onnxruntime', '--parallel', args.jobs])
    verify_sources()
    library = android / 'libonnxruntime.so'
    llvm = next((ndk / 'toolchains/llvm/prebuilt').iterdir()) / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    elf = subprocess.check_output([llvm / ('llvm-readelf' + suffix), '-h', '-l', '-d',
                                   '--dyn-syms', library], text=True)
    loads = [line.split() for line in elf.splitlines() if line.strip().startswith('LOAD ')]
    if 'AArch64' not in elf or 'OrtGetApiBase' not in elf or not loads:
        raise ValueError('Unexpected ONNX Runtime ELF architecture or API')
    if re.search(r'\((?:RUNPATH|RPATH)\)', elf):
        raise ValueError('The Android CPU runtime must not embed a library search path')
    if any(int(row[-1], 16) < 16384 or (int(row[1], 16) - int(row[2], 16)) % 16384
           for row in loads):
        raise ValueError('ONNX Runtime LOAD segments do not support 16 KiB alignment')
    needed = re.findall(r'Shared library: \[(.*?)\]', elf)
    if set(needed) - {'libdl.so', 'liblog.so', 'libm.so', 'libc.so'}:
        raise ValueError(f'Unexpected runtime binary dependency: {needed}')
    (install / 'lib').mkdir(parents=True, exist_ok=True)
    (install / 'include').mkdir(exist_ok=True)
    shutil.copyfile(library, install / 'lib/libonnxruntime.so')
    for header in (ROOT / 'vendor/onnxruntime/include/onnxruntime/core/session').glob('*.h'):
        shutil.copyfile(header, install / 'include' / header.name)
    for source in json.loads((ROOT / 'sources.json').read_text(encoding='utf-8')):
        for license_name in source['licenses']:
            destination = install / 'licenses' / Path(license_name).relative_to('vendor')
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / license_name, destination)
    toolchain_dependencies = []
    for name in ('libc++_static.a', 'libc++abi.a', 'libunwind.a',
                 'libclang_rt.builtins-aarch64-android.a', 'crtbegin_so.o', 'crtend_so.o'):
        path = Path(subprocess.check_output(
            [llvm / ('clang++' + suffix), '--target=aarch64-linux-android26',
             '-print-file-name=' + name], text=True).strip()).resolve()
        toolchain_dependencies.append({
            'name': name, 'ndk_path': path.relative_to(ndk).as_posix(),
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    manifest = {
        'onnxruntime_version': (ROOT / 'vendor/onnxruntime/VERSION_NUMBER').read_text().strip(),
        'source_inventory_sha256': hashlib.sha256((ROOT / 'sources.sha256').read_bytes()).hexdigest(),
        'source_metadata_sha256': hashlib.sha256((ROOT / 'sources.json').read_bytes()).hexdigest(),
        'build_profile_sha256': hashlib.sha256((ROOT / 'cmake/android-arm64.cmake').read_bytes()).hexdigest(),
        'abi': 'arm64-v8a', 'android_api': 26, 'ndk': NDK_VERSION,
        'cmake': cmake_version, 'backend': 'CPU', 'stl': 'c++_static',
        'library_bytes': library.stat().st_size,
        'library_sha256': hashlib.sha256(library.read_bytes()).hexdigest(),
        'needed_libraries': needed, 'load_segment_alignments': [row[-1] for row in loads],
        'host_protoc_sha256': hashlib.sha256(protoc.read_bytes()).hexdigest(),
        'toolchain_binary_dependencies': toolchain_dependencies,
    }
    (install / 'elf.txt').write_text(elf, encoding='utf-8')
    pending_identity = identity.with_suffix('.tmp')
    pending_identity.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    pending_identity.replace(identity)
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
