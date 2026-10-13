import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import ci_debug_apks


class DeviceGateTests(unittest.TestCase):
    def test_zero_single_and_both_api_selections(self):
        for selected in (('false', 'false'), ('true', 'false'), ('false', 'true'), ('true', 'true')):
            with self.subTest(selected=selected):
                artifact = '123' if 'true' in selected else ''
                self.assertEqual(selected, tuple(ci_debug_apks.selected_api('success', api, artifact)
                                                 for api in selected))

    def test_failed_upstream_fails_even_an_unselected_required_check(self):
        for selected in ('false', 'true'):
            with self.subTest(selected=selected), self.assertRaisesRegex(ValueError, 'did not succeed'):
                ci_debug_apks.selected_api('failure', selected, '')

    def test_missing_selection_or_artifact_cannot_skip_successfully(self):
        with self.assertRaisesRegex(ValueError, 'selection'):
            ci_debug_apks.selected_api('success', '', '')
        with self.assertRaisesRegex(ValueError, 'no successful APK artifact'):
            ci_debug_apks.selected_api('success', 'true', '')


class DebugApkTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name) / 'app/build/outputs/apk'
        self.sha = 'a' * 40
        for directory, package, variant in (
            ('debug', ci_debug_apks.PACKAGE, 'debug'),
            ('androidTest/debug', ci_debug_apks.PACKAGE + '.test', 'debugAndroidTest'),
        ):
            output = self.root / directory
            output.mkdir(parents=True)
            (output / 'fixture.apk').write_bytes(b'APK fixture')
            (output / 'output-metadata.json').write_text(json.dumps({
                'applicationId': package, 'variantName': variant,
                'elements': [{'outputFile': 'fixture.apk'}],
            }), encoding='utf-8')

    def prepare(self):
        ci_debug_apks.prepare(self.root, self.sha, '123', '1')

    def verify(self):
        ci_debug_apks.verify(self.root, self.sha, '123', '1')

    def test_downloaded_layout_contains_only_execution_inputs_and_build_provenance(self):
        self.prepare()
        self.verify()
        manifest = json.loads((self.root / 'ci-build.json').read_text(encoding='utf-8'))
        self.assertEqual({'debug/fixture.apk', 'debug/output-metadata.json',
                          'androidTest/debug/fixture.apk', 'androidTest/debug/output-metadata.json'},
                         set(manifest['files']))

    def test_device_rerun_uses_original_producer_attempt(self):
        self.prepare()
        env = dict(GITHUB_SHA=self.sha, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2', BUILD_ATTEMPT='1')
        with patch.dict(os.environ, env), patch.object(ci_debug_apks, 'APK_ROOT', self.root), \
                patch('sys.argv', ['ci_debug_apks.py', 'verify']), \
                patch.object(ci_debug_apks.subprocess, 'check_output', return_value=self.sha + '\n'):
            ci_debug_apks.main()

    def test_other_commit_run_or_producer_cannot_be_reused(self):
        self.prepare()
        for changed in ({'sha': 'b' * 40}, {'run_id': '124'}, {'attempt': '2'}):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, 'does not match'):
                arguments = dict(sha=self.sha, run_id='123', attempt='1')
                arguments.update(changed)
                ci_debug_apks.verify(self.root, **arguments)

    def test_missing_download_or_incomplete_test_apk_fails(self):
        with self.assertRaises(FileNotFoundError):
            self.verify()
        self.prepare()
        (self.root / 'androidTest/debug/fixture.apk').unlink()
        with self.assertRaisesRegex(ValueError, 'Expected one nonempty APK'):
            self.verify()

    def test_duplicate_or_empty_apk_cannot_be_uploaded(self):
        extra = self.root / 'debug/stale.apk'
        extra.write_bytes(b'Old APK')
        with self.assertRaisesRegex(ValueError, 'Expected one nonempty APK'):
            self.prepare()
        extra.unlink()
        (self.root / 'debug/fixture.apk').write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'Expected one nonempty APK'):
            self.prepare()

    def test_wrong_variant_metadata_is_rejected_before_upload(self):
        metadata = self.root / 'debug/output-metadata.json'
        value = json.loads(metadata.read_text(encoding='utf-8'))
        value['variantName'] = 'release'
        metadata.write_text(json.dumps(value), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'expected Debug variant'):
            self.prepare()

    def test_damaged_apk_or_metadata_fails_after_download(self):
        self.prepare()
        apk = self.root / 'debug/fixture.apk'
        original = apk.read_bytes()
        apk.write_bytes(original + b'damaged transfer')
        with self.assertRaisesRegex(ValueError, 'changed after the producer'):
            self.verify()
        apk.write_bytes(original)
        with (self.root / 'debug/output-metadata.json').open('a', encoding='utf-8') as output:
            output.write('\n')
        with self.assertRaisesRegex(ValueError, 'changed after the producer'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
