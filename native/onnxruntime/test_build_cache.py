"""Exercise install-only reuse without invoking a native compiler."""

import contextlib
import hashlib
import io
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import build_android
import verify_sources


class RuntimeCacheTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.output = self.root / 'output'
        self.install = self.output / 'install'
        self.vendor = self.root / 'vendor'
        self.ndk = self.root / 'ndk'
        self.llvm = self.ndk / 'toolchains/llvm/prebuilt/host/bin'
        self.llvm.mkdir(parents=True)
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 28.2.13676358\n')
        (self.ndk / 'static.a').write_bytes(b'toolchain library')
        files = {
            'vendor/onnxruntime/include/onnxruntime/core/session/onnxruntime_c_api.h': b'header',
            'vendor/onnxruntime/VERSION_NUMBER': b'1.17.1',
            'vendor/onnxruntime/LICENSE': b'license',
            'sources.json': b'[{"licenses": ["vendor/onnxruntime/LICENSE"]}]',
            'build_android.py': b'build recipe', 'verify_sources.py': b'inventory verifier',
            'cmake/android-arm64.cmake': b'CPU profile',
        }
        for name, content in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        self.inventory()
        self.argv = ['build_android.py', '--ndk', str(self.ndk), '--output', str(self.output),
                     '--cmake', 'cmake', '--ninja', 'ninja', '--cache-key', 'toolchain-v1']

    def inventory(self):
        (self.root / 'sources.sha256').write_text(''.join(
            f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(self.root).as_posix()}\n'
            for p in sorted(self.vendor.rglob('*')) if p.is_file()))

    def compile(self, args):
        if '--build' not in args:
            return
        directory = Path(args[args.index('--build') + 1])
        directory.mkdir(parents=True, exist_ok=True)
        if 'protoc' in args:
            protoc = directory / ('Release/protoc.exe' if build_android.os.name == 'nt' else 'protoc')
            protoc.parent.mkdir(parents=True, exist_ok=True)
            protoc.write_bytes(b'compiled protoc')
        else:
            (directory / 'libonnxruntime.so').write_bytes(b'compiled runtime')

    def tool_output(self, args, **kwargs):
        if any(str(arg).startswith('-print-file-name=') for arg in args):
            return str(self.ndk / 'static.a')
        if '--dyn-syms' in args:
            return ('AArch64\nOrtGetApiBase\n'
                    ' LOAD 0x000000 0x000000 0x000000 0x01 0x01 R E 0x4000\n'
                    ' (NEEDED) Shared library: [libc.so]\n')
        return 'cmake version 3.31.6\n' if args[0] == 'cmake' else '1.13.0\n'

    def build(self):
        with patch.object(build_android, 'ROOT', self.root), \
                patch.object(verify_sources, 'ROOT', self.root), \
                patch.object(build_android.sys, 'argv', self.argv), \
                patch.object(build_android.subprocess, 'check_output', side_effect=self.tool_output), \
                patch.object(build_android, 'run', side_effect=self.compile) as compiler, \
                contextlib.redirect_stdout(io.StringIO()):
            build_android.main()
            return compiler.call_count

    def test_install_only_hit_skips_compilers_and_still_verifies_sources(self):
        self.assertGreater(self.build(), 0)
        shutil.rmtree(self.output / 'host-protobuf')
        shutil.rmtree(self.output / 'android-arm64')
        self.assertEqual(0, self.build())
        self.assertFalse((self.output / 'host-protobuf').exists())
        self.assertFalse((self.output / 'android-arm64').exists())
        (self.vendor / 'onnxruntime/VERSION_NUMBER').write_text('unreviewed source')
        with self.assertRaisesRegex(ValueError, 'Source hash mismatch'):
            self.build()

    def test_source_recipe_and_toolchain_changes_rebuild(self):
        self.build()
        (self.vendor / 'onnxruntime/VERSION_NUMBER').write_text('updated source')
        self.inventory()
        self.assertGreater(self.build(), 0)
        (self.root / 'cmake/android-arm64.cmake').write_text('updated CPU profile')
        self.assertGreater(self.build(), 0)
        self.argv[-1] = 'toolchain-v2'
        self.assertGreater(self.build(), 0)
        self.assertEqual(0, self.build())

    def test_damaged_exports_rebuild_without_retaining_stale_files(self):
        self.build()
        (self.install / 'lib/libonnxruntime.so').write_bytes(b'corrupt library')
        (self.install / 'stale.h').write_bytes(b'unexpected export')
        self.assertGreater(self.build(), 0)
        self.assertFalse((self.install / 'stale.h').exists())
        (self.install / 'include/onnxruntime_c_api.h').unlink()
        self.assertGreater(self.build(), 0)
        (self.install / 'build-manifest.json').write_text('truncated json')
        self.assertGreater(self.build(), 0)
        self.assertEqual(0, self.build())


if __name__ == '__main__':
    unittest.main()
