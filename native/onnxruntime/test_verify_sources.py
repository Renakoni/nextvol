import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import verify_sources


class SourceIntegrityTest(unittest.TestCase):
    def test_inventory_rejects_tampering_missing_and_unlisted_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            vendor = root / 'vendor'
            vendor.mkdir()
            source = vendor / 'runtime.cc'
            source.write_bytes(b'original source\n')
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            (root / 'sources.sha256').write_text(f'{digest}  vendor/runtime.cc\n')
            with patch.object(verify_sources, 'ROOT', root):
                self.assertEqual(1, verify_sources.verify_sources())
                source.write_bytes(b'changed source\n')
                with self.assertRaisesRegex(ValueError, 'Source hash mismatch'):
                    verify_sources.verify_sources()
                source.unlink()
                with self.assertRaisesRegex(ValueError, 'Source inventory mismatch'):
                    verify_sources.verify_sources()
                source.write_bytes(b'original source\n')
                (vendor / 'unlisted.so').write_bytes(b'prebuilt')
                with self.assertRaisesRegex(ValueError, 'Source inventory mismatch'):
                    verify_sources.verify_sources()

    def test_inventory_cannot_reference_files_outside_vendor(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'sources.sha256').write_text('0' * 64 + '  ../outside.cc\n')
            with patch.object(verify_sources, 'ROOT', root):
                with self.assertRaisesRegex(ValueError, 'outside vendor'):
                    verify_sources.verify_sources()


if __name__ == '__main__':
    unittest.main()
