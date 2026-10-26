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
UI_ROOTS = {'ui/home/bookshelf/': 'bookshelf', 'ui/home/explore/': 'explore',
            'ui/home/reading/stats/': 'stats', 'ui/bangumi/': 'bangumi',
            'ui/home/settings/about/': 'about', 'ui/home/settings/licenses/': 'about',
            'ui/home/settings/logcat/': 'logs'}
JVM_TASKS = [':app:testDebugUnitTest', ':epub:test', ':source-rules:test',
             ':source-network:test', ':source-import:test', ':source-execution:test',
             ':source-compatibility:test', ':source-rhino:test', ':source-content:test',
             ':source-speech:test']
JVM_MODULES = {'epub': 'epub', 'tests/source-compatibility': 'source-compatibility',
               **{f'sources/{name}': f'source-{name}' for name in
                  ('rules', 'network', 'import', 'execution', 'rhino', 'content', 'speech')}}
# Reverse dependency closure, including test-only consumers. Every source change
# also retains compatibility integrity and the complete App JVM suite.
SOURCE_CONSUMERS = {
    'rules': ('rules', 'network', 'import', 'rhino', 'execution', 'content', 'speech'),
    'network': ('network', 'import', 'execution', 'content', 'speech'),
    'import': ('import', 'content'),
    'rhino': ('rhino', 'execution', 'content', 'speech'),
    'execution': ('execution', 'content', 'speech'),
    'content': ('content',),
    'speech': ('speech',),
}
SOURCE_CATALOG_FILES = {f'app/src/main/assets/source-catalog/{name}.json' for name in
                        ('catalog', 'Adult', 'Anime', 'Female', 'General', 'Literature', 'Platforms')}
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
    ('export', 'EpubShareInstrumentedTest'),
    ('reader', 'reader.ReaderSpacingInstrumentedTest'),
    ('reader', 'reader.ReaderLayoutInstrumentedTest'),
    ('storage', 'data.download.DownloadPersistenceInstrumentedTest'),
    ('storage', 'data.localbook.LocalBookImportInstrumentedTest'),
    ('full', 'defaultplugin.wenku8.Wenku8NetworkInstrumentedTest'),
    ('speech', 'tts.SystemSpeechInstrumentedTest'),
    ('speech', 'tts.ReadAloudBackgroundInstrumentedTest'),
    ('speech', 'tts.HttpSpeechInstrumentedTest'),
]
MINIFIED_SOURCE = PACKAGE + 'benchmark.explore.MinifiedSourceRuntimeTest'
MINIFIED_SPEECH = PACKAGE + 'benchmark.tts.ReadAloudSmokeTest'


def source_module_for_path(path):
    return next((name for name in SOURCE_CONSUMERS
                 if path.startswith(f'sources/{name}/src/main/kotlin/') and path.endswith('.kt')), None)


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
    if path.startswith('app/src/test/resources/backups/'):
        return 'backup'
    source_module = source_module_for_path(path)
    if source_module:
        return 'speech' if source_module == 'speech' else 'source'
    if path in SOURCE_CATALOG_FILES:
        return 'source'
    for root in KOTLIN_ROOTS:
        if path.startswith(root):
            relative = path[len(root):]
            if relative.startswith('data/update/') or (root == KOTLIN_ROOTS[0] and (
                    relative.startswith('ui/home/settings/updates/') or relative in {
                        'ui/dialog/UpdatesAvailableDialog.kt',
                        'ui/dialog/UpdatesAvailableDialogViewModel.kt'})):
                return 'update'
            if relative.startswith('data/export/') or (root == KOTLIN_ROOTS[0] and (
                    relative.startswith('ui/export/') or relative in {
                        'data/work/ExportBookToEPUBWork.kt',
                        'ui/book/detail/ExportBottomSheet.kt',
                        'ui/book/detail/ExportSettings.kt',
                        'ui/book/detail/EpubShareActivity.kt'})):
                return 'export'
            if root == KOTLIN_ROOTS[1] and relative in {
                    'data/work/EpubExportWorkerAdapterTest.kt',
                    'data/work/EpubExportRegressionTest.kt',
                    'data/work/EpubXhtmlWorkerTest.kt',
                    'data/work/SourceExportWorkerTest.kt',
                    'ui/book/detail/DetailExportTest.kt'}:
                return 'export'
            if relative.startswith('data/backup/') or (root == KOTLIN_ROOTS[0] and relative in {
                    'data/work/ExportDataWork.kt', 'data/work/ImportDataWork.kt',
                    'data/work/SaveBookshelfWork.kt', 'ui/components/BackupFailureMessage.kt',
                    'ui/home/settings/ExportContext.kt', 'ui/home/settings/ExportUserDataDialog.kt',
                    'ui/home/settings/ExportUserDataDialogViewModel.kt',
                    'ui/home/settings/ImportUserDataDialog.kt'}):
                return 'backup'
            if relative.startswith('ui/bookmanager/') or (root == KOTLIN_ROOTS[0] and
                    relative.startswith(('data/storage/', 'ui/storagemanager/'))):
                return 'storage'
            if relative.startswith('ui/book/reader/'):
                return 'reader'
            if relative.startswith(('tts/', 'ui/tts/')):
                return 'speech'
            if relative.startswith(('ui/home/discovery/', 'ui/home/categories/')):
                return 'catalog'
            for prefix, scope in UI_ROOTS.items():
                if relative.startswith(prefix):
                    # These settings screens currently have cross-directory tests.
                    # A new test in their own package must not fall outside the filter.
                    return 'full' if root == KOTLIN_ROOTS[1] and scope in {'about', 'logs'} else scope
    if path.startswith('app/src/androidTest/kotlin/indi/renakoni/nextvol/reader/'):
        return 'reader'
    if path.startswith('app/src/androidTest/kotlin/indi/renakoni/nextvol/tts/'):
        return 'speech'
    if path.startswith('epub/src/main/') or path == (
            'app/src/androidTest/kotlin/indi/renakoni/nextvol/EpubShareInstrumentedTest.kt'):
        return 'export'
    if path in {
            'app/src/androidTest/kotlin/indi/renakoni/nextvol/data/download/DownloadPersistenceInstrumentedTest.kt',
            'app/src/androidTest/kotlin/indi/renakoni/nextvol/data/localbook/LocalBookImportInstrumentedTest.kt'}:
        return 'storage'
    return 'full'


def scopes_for_paths(paths):
    scopes = {classify(path) for path in paths} - {'docs'}
    if not paths or 'full' in scopes:
        return {'full'}
    if any('speech' in SOURCE_CONSUMERS.get(source_module_for_path(path), ()) for path in paths):
        scopes.add('speech')
    # Speech follows reader position and owns controls inside the reader.
    if 'speech' in scopes:
        scopes.add('reader')
    return scopes


def qualified(classes):
    return ','.join(PACKAGE + name for name in classes)


def device_classes(scopes, api):
    if 'speech' in scopes:
        scopes = scopes | {'reader'}
    # App readers/downloads also consume source modules. Keep the existing Debug
    # coverage until those device boundaries have been independently reviewed.
    full = bool(scopes & {'full', 'source'})
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
    if 'export' in scopes:
        tasks.append(':epub:test')
    if active and not full and 'source' not in scopes:
        tasks.append('-PciTestScope=' + ','.join(sorted(scopes)))
    minified = []
    if full or scopes & {'catalog', 'source'}:
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
    source_production = False
    for path in paths:
        source_module = source_module_for_path(path)
        if source_module or path in SOURCE_CATALOG_FILES:
            consumers = SOURCE_CONSUMERS[source_module] if source_module else ('content',)
            modules.update(f'source-{name}' for name in consumers)
            modules.add('source-compatibility')
            source_production = True
            production.append(path)
            continue
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
    if source_production:
        app_scopes.add('full')
    if app_scopes:
        tasks.add(':app:testDebugUnitTest')
    ordered_tasks = [task for task in JVM_TASKS if task in tasks]
    if app_scopes and 'full' not in app_scopes:
        ordered_tasks.append('-PciTestScope=' + ','.join(sorted(app_scopes)))
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
        if not scopes <= {'full', 'ui', 'catalog', 'reader', 'speech', 'source', 'update', 'export', 'backup', 'storage', 'docs', *UI_ROOTS.values()}:
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
