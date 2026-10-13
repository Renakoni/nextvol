"""Select existing checks from reviewed path boundaries; unknown paths run everything."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


PACKAGE = 'indi.renakoni.nextvol.'
KOTLIN_ROOTS = tuple(f'app/src/{source}/kotlin/indi/renakoni/nextvol/'
                     for source in ('main', 'test'))
# Shared UI, navigation, resources, and unreviewed data/runtime code
# intentionally fall through to full coverage.
UI_ROOTS = ('ui/home/bookshelf/', 'ui/home/explore/',
            'ui/home/reading/stats/', 'ui/bangumi/',
            'ui/home/settings/about/', 'ui/home/settings/licenses/',
            'ui/home/settings/logcat/')
JVM_TASKS = [':app:testDebugUnitTest', ':epub:test', ':source-rules:test',
             ':source-network:test', ':source-import:test', ':source-execution:test',
             ':source-compatibility:test', ':source-rhino:test', ':source-content:test',
             ':source-speech:test']
JVM_MODULES = {'epub': 'epub', 'tests/source-compatibility': 'source-compatibility',
               **{f'sources/{name}': f'source-{name}' for name in
                  ('rules', 'network', 'import', 'execution', 'rhino', 'content', 'speech')}}
SOURCE_24 = [f'sourceexecution.{name}InstrumentedTest' for name in (
    'IsolatedExecution', 'SourceAccount', 'SourceBrowser', 'SourceVpn',
    'SourceCompatibility', 'PixivLifecycle', 'IsolatedConcurrency')]
SOURCE_35 = [f'sourceexecution.{name}InstrumentedTest' for name in (
    'NativeBrowser', 'NativeBrowserPool', 'NativeBrowserAdmissionCookie',
    'NativeBrowserStorage', 'NativeBrowserRoute', 'SourceVerification',
    'SourceImageRecovery', 'RuleImageRecovery', 'ImageUserAgent')]
READER_WIDE = ['reader.ReaderPositionInstrumentedTest', 'reader.ReaderSpreadInstrumentedTest']
# Keep the existing full-suite class order while selecting individual scopes.
EXTRA_24 = [
    ('reader', 'sourceexecution.ReadingPanelInstrumentedTest'),
    ('full', 'EpubShareInstrumentedTest'),
    ('reader', 'reader.ReaderSpacingInstrumentedTest'),
    ('reader', 'reader.ReaderLayoutInstrumentedTest'),
    ('full', 'data.download.DownloadPersistenceInstrumentedTest'),
    ('full', 'data.localbook.LocalBookImportInstrumentedTest'),
    ('full', 'defaultplugin.wenku8.Wenku8NetworkInstrumentedTest'),
    ('speech', 'tts.SystemSpeechInstrumentedTest'),
    ('speech', 'tts.ReadAloudBackgroundInstrumentedTest'),
    ('speech', 'tts.HttpSpeechInstrumentedTest'),
]
MINIFIED_SOURCE = PACKAGE + 'benchmark.explore.MinifiedSourceRuntimeTest'
MINIFIED_SPEECH = PACKAGE + 'benchmark.tts.ReadAloudSmokeTest'


def classify(path):
    parts = Path(path).parts
    if path.endswith('.md') and (len(parts) == 1 or
                                (len(parts) == 2 and parts[0] == '.github') or
                                path.startswith('docs/')):
        return 'docs'
    if path in {'.github/FUNDING.yml', '.github/ISSUE_TEMPLATE/bug_report.yml',
                '.github/ISSUE_TEMPLATE/feature_request.yml',
                '.github/ISSUE_TEMPLATE/maintenance_task.yml'}:
        return 'docs'
    for root in KOTLIN_ROOTS:
        if path.startswith(root):
            relative = path[len(root):]
            if relative.startswith(('data/update/', 'ui/home/settings/updates/')) or relative in {
                    'ui/dialog/UpdatesAvailableDialog.kt',
                    'ui/dialog/UpdatesAvailableDialogViewModel.kt'}:
                return 'update'
            if relative.startswith('ui/book/reader/'):
                return 'reader'
            if relative.startswith(('tts/', 'ui/tts/')):
                return 'speech'
            if relative.startswith(('ui/home/discovery/', 'ui/home/categories/')):
                return 'catalog'
            if relative.startswith(UI_ROOTS):
                return 'ui'
    if path.startswith('app/src/androidTest/kotlin/indi/renakoni/nextvol/reader/'):
        return 'reader'
    if path.startswith('app/src/androidTest/kotlin/indi/renakoni/nextvol/tts/'):
        return 'speech'
    return 'full'


def scopes_for_paths(paths):
    scopes = {classify(path) for path in paths} - {'docs'}
    if not paths or 'full' in scopes:
        return {'full'}
    # Speech follows reader position and owns controls inside the reader.
    if 'speech' in scopes:
        scopes.add('reader')
    if 'catalog' in scopes:
        scopes.add('ui')
    return scopes


def qualified(classes):
    return ','.join(PACKAGE + name for name in classes)


def device_classes(scopes, api):
    if 'speech' in scopes:
        scopes = scopes | {'reader'}
    full = 'full' in scopes
    main = list(SOURCE_35 if api == 35 else SOURCE_24) if full else []
    reader = full or 'reader' in scopes
    if api == 24:
        main += [name for scope, name in EXTRA_24 if full or scope in scopes]
    return qualified(main), qualified(READER_WIDE if reader else [])


def plan(scopes):
    active = bool(scopes)
    full = 'full' in scopes
    tasks = JVM_TASKS if full else [':app:testDebugUnitTest'] if active else []
    tasks = list(tasks)
    if 'speech' in scopes:
        tasks.append(':source-speech:test')
    if active and not full:
        tasks.append('-PciTestScope=' + ','.join(sorted(scopes - {'catalog'})))
    minified = []
    if full or 'catalog' in scopes:
        minified.append(MINIFIED_SOURCE)
    if full or 'speech' in scopes:
        minified.append(MINIFIED_SPEECH)
    return {
        'scopes': ','.join(sorted(scopes)) or 'docs',
        'device-scopes': ','.join(sorted(scopes)) or 'docs',
        'jvm': active,
        'gradle-args': ' '.join(tasks),
        'device-24': any(device_classes(scopes, 24)),
        'device-35': any(device_classes(scopes, 35)),
        'release': active,
        'lint': active,
        'minified': bool(minified),
        'minified-classes': ','.join(minified),
    }


def plan_for_paths(paths):
    if not paths:
        return plan({'full'})
    production = []
    test_scopes, device_scopes, modules, minified = set(), set(), set(), set()
    test_inputs = False
    for path in paths:
        module = next((name for root, name in JVM_MODULES.items()
                       if path.startswith(root + '/src/test/')), None)
        if path.startswith('app/src/test/'):
            test_scopes.update(scopes_for_paths([path]))
            modules.add('source-compatibility')
        elif path.startswith('app/src/androidTest/'):
            device_scopes.update(scopes_for_paths([path]))
            modules.add('source-compatibility')
        elif path.startswith('sources/content/src/testFixtures/'):
            test_scopes.add('full')
            modules.add('source-content')
            device_scopes.add('full')
        elif path.startswith('tests/source-compatibility/browser/'):
            modules.update(('source-content', 'source-compatibility'))
            device_scopes.add('full')
        elif path.startswith('tests/source-compatibility/fixtures/native-routing/site/'):
            device_scopes.add('full')
        elif path.startswith('tests/source-compatibility/reference/'):
            modules.add('source-compatibility')
        elif module:
            # The compatibility manifest references tests across module roots.
            # Selecting the entire owner also covers shared test helpers/deletions.
            modules.update((module, 'source-compatibility'))
            if path.startswith('sources/rhino/src/test/resources/'):
                device_scopes.add('full')
        elif path in (
                'tests/benchmark/src/main/kotlin/' + MINIFIED_SOURCE.replace('.', '/') + '.kt',
                'tests/benchmark/src/main/kotlin/' + MINIFIED_SPEECH.replace('.', '/') + '.kt'):
            minified.add(path[len('tests/benchmark/src/main/kotlin/'):-3].replace('/', '.'))
        else:
            production.append(path)
            continue
        test_inputs = True

    scopes = scopes_for_paths(production) if production else set()
    result = plan(scopes)
    tasks = {arg for arg in result['gradle-args'].split() if arg.startswith(':')}
    tasks.update(f':{module}:test' for module in modules)
    app_scopes = set(scopes) if ':app:testDebugUnitTest' in tasks else set()
    app_scopes.update(test_scopes)
    if app_scopes:
        tasks.add(':app:testDebugUnitTest')
    ordered_tasks = [task for task in JVM_TASKS if task in tasks]
    if app_scopes and 'full' not in app_scopes:
        ordered_tasks.append('-PciTestScope=' + ','.join(sorted(app_scopes - {'catalog'})))
    result['jvm'] = bool(tasks)
    result['gradle-args'] = ' '.join(ordered_tasks)
    device_scopes.update(scopes)
    result['device-scopes'] = ','.join(sorted(device_scopes)) or 'docs'
    for api in (24, 35):
        result[f'device-{api}'] = any(device_classes(device_scopes, api))
    minified.update(filter(None, result['minified-classes'].split(',')))
    result['minified-classes'] = ','.join(sorted(minified))
    result['minified'] = bool(minified)
    result['lint'] = result['lint'] or test_inputs
    labels = scopes | test_scopes | device_scopes
    result['scopes'] = 'full' if 'full' in labels else ','.join(sorted(labels)) or ('tests' if test_inputs else 'docs')
    return result


def changed_paths(event_name, event):
    if event_name == 'pull_request':
        base = event['pull_request']['base']['sha']
    elif event_name == 'merge_group':
        base = event['merge_group']['base_sha']
    else:
        raise ValueError('manual/other event: full checks')
    if not re.fullmatch(r'[0-9a-fA-F]{40}', base):
        raise ValueError('invalid base SHA')
    exists = subprocess.run(['git', 'cat-file', '-e', base + '^{commit}'],
                            capture_output=True, timeout=30)
    if exists.returncode:
        # Fetch only the base, not the repository's historical large binary assets.
        subprocess.run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', base],
                       check=True, timeout=120)
    # HEAD is the tested PR merge tree (or merge-group tree). No API file-list cap;
    # --no-renames includes both sides of moves, including moves across scopes.
    diff = subprocess.check_output(
        ['git', 'diff', '--no-ext-diff', '--no-renames', '--name-only', '-z', base, 'HEAD', '--'],
        timeout=30)
    return [path.decode('utf-8') for path in diff.split(b'\0') if path]


def github_plan():
    try:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
        paths = changed_paths(os.environ['GITHUB_EVENT_NAME'], event)
        return plan_for_paths(paths), f'{len(paths)} changed paths compared with the event base'
    except (KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        return plan({'full'}), f'Full checks: {error}'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--paths', nargs='*', help='Preview checks for repository-relative paths')
    parser.add_argument('--check', choices=('jvm', 'device-24', 'device-35', 'release', 'lint', 'minified'))
    parser.add_argument('--device-api', type=int)
    parser.add_argument('--scopes', default='full')
    args = parser.parse_args()
    if args.device_api:
        scopes = set(args.scopes.split(','))
        if not scopes <= {'full', 'ui', 'catalog', 'reader', 'speech', 'update', 'docs'}:
            parser.error('unknown test scope')
        print('\n'.join(device_classes(scopes, args.device_api)))
        return
    if args.paths is not None:
        result, reason = plan_for_paths(args.paths), 'Explicit paths'
    else:
        result, reason = github_plan()
    print(json.dumps(dict(result, reason=reason), indent=2))
    if args.check:
        result['run'] = result[args.check]
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            for key, value in result.items():
                output.write(f'{key}={str(value).lower() if isinstance(value, bool) else value}\n')
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write(f'### Check selection: {args.check}\n\n')
            summary.write(f"Scopes: `{result['scopes']}`. {reason}\n\n")
            summary.write('Check | Selected\n--- | ---\n')
            for check in ('jvm', 'device-24', 'device-35', 'release', 'lint', 'minified'):
                summary.write(f'{check} | {result[check]}\n')
            summary.write(f"\nGradle: `{result['gradle-args'] or 'none'}`\n")


if __name__ == '__main__':
    main()
