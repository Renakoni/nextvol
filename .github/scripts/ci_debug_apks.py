"""Gate required device checks and validate this workflow's shared Debug APKs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


APK_ROOT = Path('app/build/outputs/apk')
PACKAGE = 'indi.renakoni.nextvol.debug'


def selected_api(build_result, selected, artifact_id):
    if build_result != 'success':
        raise ValueError(f'Debug APK selection/build/upload did not succeed: {build_result}')
    if selected not in ('true', 'false'):
        raise ValueError(f'Missing or invalid device selection: {selected!r}')
    if selected == 'true' and not re.fullmatch(r'[1-9][0-9]*', artifact_id):
        raise ValueError('Selected device has no successful APK artifact')
    return selected


def apk_files(root):
    files = []
    for directory, package, variant in (
        ('debug', PACKAGE, 'debug'),
        ('androidTest/debug', PACKAGE + '.test', 'debugAndroidTest'),
    ):
        apks = list((root / directory).glob('*.apk'))
        if len(apks) != 1 or not apks[0].is_file() or not apks[0].stat().st_size:
            raise ValueError(f'Expected one nonempty APK in {directory}')
        metadata_path = root / directory / 'output-metadata.json'
        metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
        if (metadata['applicationId'] != package or metadata['variantName'] != variant or
                [item['outputFile'] for item in metadata['elements']] != [apks[0].name]):
            raise ValueError(f'APK metadata does not describe the expected Debug variant: {directory}')
        files.extend((apks[0], metadata_path))
    return files


def file_hashes(root):
    hashes = {}
    for path in apk_files(root):
        with path.open('rb') as source:
            hashes[path.relative_to(root).as_posix()] = hashlib.file_digest(source, 'sha256').hexdigest()
    return hashes


def provenance(sha, run_id, attempt):
    return {'sha': sha, 'run-id': run_id, 'build-attempt': attempt, 'variant': 'debug'}


def prepare(root, sha, run_id, attempt):
    manifest = dict(provenance(sha, run_id, attempt), files=file_hashes(root))
    (root / 'ci-build.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')


def verify(root, sha, run_id, attempt):
    manifest = json.loads((root / 'ci-build.json').read_text(encoding='utf-8'))
    for key, value in provenance(sha, run_id, attempt).items():
        if manifest.get(key) != value:
            raise ValueError(f'APK build does not match this workflow/tree: {key}')
    if manifest['files'] != file_hashes(root):
        raise ValueError('APK files or metadata changed after the producer built them')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('gate', 'prepare', 'verify'))
    args = parser.parse_args()
    if args.command == 'gate':
        selected = selected_api(os.environ['BUILD_RESULT'], os.environ['SELECTED'],
                                os.environ['ARTIFACT_ID'])
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write(f'run={selected}\n')
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write('Device tests selected; using the successful APK build.\n' if selected == 'true'
                          else 'No device tests selected for this API; no APK download or emulator.\n')
        return
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if sha != os.environ['GITHUB_SHA']:
        raise ValueError('Checkout does not match the tested GitHub tree')
    run_id = os.environ['GITHUB_RUN_ID']
    if args.command == 'prepare':
        attempt = os.environ['GITHUB_RUN_ATTEMPT']
        prepare(APK_ROOT, sha, run_id, attempt)
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write(f'build-attempt={attempt}\n')
    else:
        verify(APK_ROOT, sha, run_id, os.environ['BUILD_ATTEMPT'])


if __name__ == '__main__':
    main()
