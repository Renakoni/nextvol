"""Build the source-owned SoundTouch processor for every application ABI."""
import argparse
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent


def run(arguments):
    subprocess.run([str(value) for value in arguments], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--output', type=Path, default=ROOT / 'build')
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    output = args.output.resolve()
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
        build = output / abi
        run([args.cmake, '-S', ROOT, '-B', build, '-G', 'Ninja',
             '-DCMAKE_MAKE_PROGRAM=' + args.ninja,
             '-DCMAKE_TOOLCHAIN_FILE=' + (args.ndk.resolve() / 'build/cmake/android.toolchain.cmake').as_posix(),
             '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-24', '-DANDROID_STL=c++_static',
             '-DCMAKE_BUILD_TYPE=Release'])
        run([args.cmake, '--build', build, '--parallel', args.jobs])
        target = output / 'jniLibs' / abi / 'libnextvol-audio.so'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build / target.name, target)


if __name__ == '__main__':
    main()
