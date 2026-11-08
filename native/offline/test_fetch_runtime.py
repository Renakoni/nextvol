"""Verify SDK archive reuse and failed updates using a tiny local ZIP."""

import contextlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import fetch_runtime


class RuntimeArchiveTests(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.source = self.root / "source.zip"
        self.archive = self.root / "cache/runtime.zip"
        self.output = self.root / "output"
        self.library = b"native SDK"
        self.write_bundle(len(self.library))
        self.digest = fetch_runtime.sha256(self.source)

    def write_bundle(self, size):
        manifest = {"abi": "arm64-v8a", "libraries": {"libtest.so": {"bytes": size}}}
        with zipfile.ZipFile(self.source, "w") as bundle:
            bundle.writestr("jniLibs/arm64-v8a/libtest.so", self.library)
            bundle.writestr("assets/offline-native/runtime-manifest.json", json.dumps(manifest))

    def prepare(self):
        arguments = ["fetch_runtime.py", "--url", self.source.as_uri(), "--sha256", self.digest,
                     "--archive", str(self.archive), "--output", str(self.output)]
        with patch("sys.argv", arguments), contextlib.redirect_stdout(io.StringIO()):
            fetch_runtime.main()

    def test_cached_archive_restores_a_fresh_output_without_downloading(self):
        self.prepare()
        shutil.rmtree(self.output)
        with patch.object(fetch_runtime.urllib.request, "urlopen", side_effect=AssertionError("network")):
            self.prepare()
        self.assertEqual(self.library, (self.output / "jniLibs/arm64-v8a/libtest.so").read_bytes())
        self.assertEqual(self.digest, fetch_runtime.sha256(self.archive))

    def test_bad_download_preserves_output_and_corrupt_cache_can_be_repaired(self):
        self.prepare()
        original = self.source.read_bytes()
        self.archive.write_bytes(b"damaged cache")
        self.source.write_bytes(b"replaced release attachment")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.prepare()
        self.assertEqual(self.library, (self.output / "jniLibs/arm64-v8a/libtest.so").read_bytes())
        self.source.write_bytes(original)
        self.prepare()
        self.assertEqual(self.digest, fetch_runtime.sha256(self.archive))

    def test_changed_digest_fetches_new_bundle_and_rejects_incomplete_exports(self):
        self.prepare()
        self.write_bundle(len(self.library) + 1)
        self.digest = fetch_runtime.sha256(self.source)
        with self.assertRaisesRegex(ValueError, "Incomplete runtime library"):
            self.prepare()
        self.assertEqual(self.library, (self.output / "jniLibs/arm64-v8a/libtest.so").read_bytes())


if __name__ == "__main__":
    unittest.main()
