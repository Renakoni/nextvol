import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile


SCRIPT = Path(__file__).with_name('prepare-android-sdk.sh').resolve()
ARCHIVES = {
    'platform-37.0_r02.zip': ('android-37.0', 'platforms/android-37.0'),
    'build-tools_r36_linux.zip': ('android-16', 'build-tools/36.0.0'),
    'platform-tools_r37.0.1-linux.zip': ('platform-tools', 'platform-tools'),
    'emulator-linux_x64-15917651.zip': ('emulator', 'emulator'),
    'x86_64-24_r27.zip': ('x86_64', 'system-images/android-24/google_apis/x86_64'),
    'x86_64-35_r09.zip': ('x86_64', 'system-images/android-35/google_apis/x86_64'),
}


class PrepareSdkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='android-sdk-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for archive, (directory, _) in ARCHIVES.items():
            with zipfile.ZipFile(self.root / archive, 'w') as z:
                z.writestr(f'{directory}/source.properties', f'fixture={archive}\n')
                z.writestr(f'{directory}/payload', 'complete')
        curl = self.bin / 'curl'
        curl.write_text('''#!/usr/bin/env bash
set -eu
url=${@: -1}
echo "$url" >> "$CASE_ROOT/downloads"
if [[ "${FAIL_DOWNLOAD:-}" == 1 ]]; then exit 22; fi
while [[ "$1" != --output ]]; do shift; done
cp "$CASE_ROOT/${url##*/}" "$2"
''')
        curl.chmod(0o755)
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root), CASE_ROOT=str(self.root),
                        GITHUB_ENV=str(self.root / 'env'), GITHUB_PATH=str(self.root / 'path'),
                        PATH=str(self.bin) + os.pathsep + os.environ['PATH'])

    def run_prepare(self, api=''):
        return subprocess.run(['bash', str(SCRIPT), api], cwd=self.root, env=self.env,
                              capture_output=True, text=True, timeout=15)

    def test_build_job_installs_only_sdk_and_cache_hit_never_downloads(self):
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        downloads = (self.root / 'downloads').read_text().splitlines()
        self.assertEqual(3, len(downloads))
        for archive, (_, destination) in list(ARCHIVES.items())[:3]:
            installed = self.root / 'hnovel-android-sdk' / destination
            self.assertEqual('complete', (installed / 'payload').read_text())
            self.assertIn(archive, (installed / 'source.properties').read_text())
            package = ET.parse(installed / 'package.xml').find('localPackage')
            self.assertEqual(destination.replace('/', ';'), package.get('path'))
        self.assertIn(f'ANDROID_HOME={self.root}/hnovel-android-sdk', (self.root / 'env').read_text())
        self.env['FAIL_DOWNLOAD'] = '1'
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(downloads, (self.root / 'downloads').read_text().splitlines())

    def test_each_api_downloads_only_its_exact_image_revision(self):
        for api, archive in [('24', 'x86_64-24_r27.zip'), ('35', 'x86_64-35_r09.zip')]:
            with self.subTest(api=api):
                before = (self.root / 'downloads').read_text() if (self.root / 'downloads').exists() else ''
                result = self.run_prepare(api)
                self.assertEqual(0, result.returncode, result.stderr)
                added = (self.root / 'downloads').read_text()[len(before):]
                self.assertIn('/sys-img/google_apis/' + archive, added)
                other = '35_r09' if api == '24' else '24_r27'
                self.assertNotIn(other, added)

    def assert_failed_without_export(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'env').exists())
        self.assertFalse((self.root / 'path').exists())
        self.assertFalse(list(self.root.glob('android-package.*')))

    def test_download_failure_does_not_publish_partial_environment(self):
        self.env['FAIL_DOWNLOAD'] = '1'
        self.assert_failed_without_export(self.run_prepare())

    def test_corrupt_archive_does_not_publish_partial_environment(self):
        (self.root / 'platform-37.0_r02.zip').write_bytes(b'not a zip')
        self.assert_failed_without_export(self.run_prepare())

    def test_unexpected_archive_layout_fails_before_install(self):
        with zipfile.ZipFile(self.root / 'platform-37.0_r02.zip', 'w') as z:
            z.writestr('wrong/payload', 'missing source.properties')
        self.assert_failed_without_export(self.run_prepare())


if __name__ == '__main__':
    unittest.main()
