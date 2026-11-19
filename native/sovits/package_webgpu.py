"""Package Voicebox's V2 arm64 WebGPU build alongside NextVol's existing CPU runtime."""
import argparse
import hashlib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('aar', type=Path, help='onnxruntime-v2plus-fp32.aar')
parser.add_argument('ort_source', type=Path, help='ONNX Runtime v1.27.0 license directory')
args = parser.parse_args()
if hashlib.sha256(args.aar.read_bytes()).hexdigest() != 'db6d7eba5c8c2f1debc61874b2e9abafd6b581a35c679ff5390b0442de89e698':
    parser.error('Unexpected WebGPU runtime build')

with ZipFile(args.aar) as source, ZipFile(Path(__file__).with_name('onnxruntime-webgpu.aar'), 'w') as bundle:
    def put(name, data):
        bundle.writestr(ZipInfo(name), data, compress_type=ZIP_DEFLATED)

    for name in ('AndroidManifest.xml', 'R.txt', 'classes.jar', 'jni/arm64-v8a/libonnxruntime.so',
                 'jni/arm64-v8a/libonnxruntime4j_jni.so'):
        data = source.read(name)
        if name.endswith('.so'):
            # Rename SONAME, version-table references and JNI DT_NEEDED in place, retaining ELF alignment.
            original = b'libonnxruntime.so\0'
            if original not in data:
                raise ValueError(f'Missing runtime reference in {name}')
            data = data.replace(original, b'libortwebgpu.so\0'.ljust(len(original), b'\0'))
        put(name.replace('libonnxruntime.so', 'libortwebgpu.so'), data)
    for name in ('LICENSE', 'ThirdPartyNotices.txt'):
        put('assets/gpt-sovits/licenses/onnxruntime/' + name, (args.ort_source / name).read_bytes())
    put('proguard.txt', b'-keep class ai.onnxruntime.** { *; }\n')
