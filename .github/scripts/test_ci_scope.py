import fnmatch
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import ci_scope


ROOT = Path(__file__).resolve().parents[2]
MAIN = ci_scope.KOTLIN_ROOTS[0]


class ScopeTests(unittest.TestCase):
    def select(self, *paths):
        return ci_scope.plan_for_paths(paths)

    def test_update_mixes_by_union_and_shared_entries_remain_full(self):
        self.assertIn('-PciTestScope=reader,update', self.select(
            MAIN + 'data/update/UpdateSource.kt', MAIN + 'ui/book/reader/ReaderScreen.kt')['gradle-args'])
        for path in ('MainActivity.kt', 'ui/NextVolApp.kt', 'ui/home/settings/SettingStates.kt',
                     'ui/home/settings/Navigation.kt', 'data/web/rules/SourceRevisionUpdates.kt',
                     'data/work/CheckUpdateWork.kt'):
            with self.subTest(path=path):
                self.assertEqual(ci_scope.plan({'full'}), self.select(
                    MAIN + 'data/update/UpdateSource.kt', MAIN + path))

    def test_positive_app_filters_have_existing_test_classes(self):
        build = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
        filters = build.split('val uiTests =', 1)[1].split('require(scopes.all', 1)[0]
        patterns = re.findall(r'"((?:ui|data|tts|defaultplugin)\.[A-Za-z0-9.*]+)"', filters)
        classes = [ci_scope.PACKAGE + path.relative_to(ROOT / 'app/src/test/kotlin/indi/renakoni/nextvol')
                   .with_suffix('').as_posix().replace('/', '.')
                   for path in (ROOT / 'app/src/test/kotlin/indi/renakoni/nextvol').rglob('*.kt')
                   if '@Test' in path.read_text(encoding='utf-8')]
        self.assertTrue(patterns)
        for pattern in patterns:
            self.assertTrue(any(fnmatch.fnmatchcase(name, ci_scope.PACKAGE + pattern) for name in classes), pattern)
        self.assertNotIn('excludeTestsMatching', build)

    def test_export_unions_update_and_preserves_shared_boundaries(self):
        self.assertIn('-PciTestScope=export,update', self.select(
            MAIN + 'data/export/EpubShareFiles.kt', MAIN + 'data/update/UpdateSource.kt')['gradle-args'])
        for path in ('data/image/SourceImage.kt', 'data/localbook/EpubBookParser.kt',
                     'data/book/BookRepository.kt', 'ui/book/detail/DetailViewModel.kt'):
            self.assertEqual(ci_scope.plan({'full'}), self.select(
                MAIN + 'data/export/EpubShareFiles.kt', MAIN + path))
        for relative in ('ui/export/NewShareTest.kt', 'data/work/BookWorkIdentityTest.kt'):
            self.assertNotIn('-PciTestScope=', self.select(ci_scope.KOTLIN_ROOTS[1] + relative)['gradle-args'])

    def test_backup_unions_export_and_keeps_shared_persistence_and_entries_broad(self):
        plan = self.select(MAIN + 'data/backup/BackupArchive.kt', MAIN + 'data/export/EpubShareFiles.kt')
        self.assertIn('-PciTestScope=backup,export', plan['gradle-args'])
        self.assertEqual((ci_scope.PACKAGE + 'EpubShareInstrumentedTest', ''),
                         ci_scope.device_classes(set(plan['device-scopes'].split(',')), 24))
        for path in ('data/local/room/NextVolDatabase.kt', 'data/userdata/UserDataRepository.kt',
                     'data/ExternalFiles.kt', 'ui/ExternalFileViewModel.kt',
                     'ui/home/settings/SettingsViewModel.kt', 'ui/home/settings/Navigation.kt'):
            self.assertEqual(ci_scope.plan({'full'}), self.select(
                MAIN + 'data/backup/BackupArchive.kt', MAIN + path))
        for relative in ('data/local/SourceIdentityRoomTest.kt', 'data/download/LegacyDownloadSchema.kt'):
            tests = self.select(ci_scope.KOTLIN_ROOTS[1] + relative)
            self.assertNotIn('-PciTestScope=', tests['gradle-args'])
            self.assertFalse(tests['device-24'])

    def test_storage_unions_backup_without_claiming_shared_contracts(self):
        self.assertIn('-PciTestScope=backup,storage', self.select(
            MAIN + 'data/storage/StorageBookQuery.kt', MAIN + 'data/backup/BackupArchive.kt')['gradle-args'])
        for path in ('data/local/room/dao/StorageStatsDao.kt', 'data/book/BookIdentity.kt',
                     'data/book/BookRepository.kt', 'data/local/UserReadingDataRepository.kt'):
            self.assertEqual(ci_scope.plan({'full'}), self.select(
                MAIN + 'data/storage/StorageBookQuery.kt', MAIN + path))
        unknown_test = self.select(ci_scope.KOTLIN_ROOTS[1] + 'ui/storagemanager/NewStorageTest.kt')
        self.assertNotIn('-PciTestScope=', unknown_test['gradle-args'])
        device = self.select('app/src/androidTest/kotlin/indi/renakoni/nextvol/data/localbook/LocalBookImportInstrumentedTest.kt')
        self.assertTrue(device['device-24'])
        self.assertFalse(device['device-35'])
        self.assertFalse(device['release'])

    def test_independent_layouts_skip_reader_devices_and_library_tasks(self):
        for path, scope in (('ui/home/bookshelf/home/BookshelfScreen.kt', 'bookshelf'),
                            ('ui/home/reading/stats/StatsOverviewScreen.kt', 'stats'),
                            ('ui/home/explore/search/SearchHubScreen.kt', 'explore'),
                            ('ui/bangumi/BangumiScreen.kt', 'bangumi'),
                            ('ui/home/settings/about/AboutSettingsScreen.kt', 'about'),
                            ('ui/home/settings/logcat/LogcatScreen.kt', 'logs')):
            with self.subTest(path=path):
                plan = self.select(MAIN + path)
                self.assertEqual(scope, plan['scopes'])
                self.assertEqual('' if scope == 'about' else
                                 ':app:testDebugUnitTest -PciTestScope=' + scope, plan['gradle-args'])
                self.assertEqual(scope != 'about', plan['jvm'])
                for check in ('lint', 'release'):
                    self.assertTrue(plan[check])
                for check in ('device-24', 'device-35', 'minified'):
                    self.assertFalse(plan[check])
        self.assertEqual(':app:testDebugUnitTest -PciTestScope=logs', self.select(
            MAIN + 'ui/home/settings/about/AboutSettingsScreen.kt',
            MAIN + 'ui/home/settings/logcat/LogcatScreen.kt')['gradle-args'])
        mixed = self.select(MAIN + 'ui/home/settings/licenses/LicensesScreen.kt',
                            'sources/rules/src/test/kotlin/hnovel/rules/RuleEvaluatorTest.kt')
        self.assertNotIn(':app:testDebugUnitTest', mixed['gradle-args'])
        self.assertIn(':source-rules:test', mixed['gradle-args'])
        self.assertTrue(mixed['lint'])
        self.assertTrue(mixed['release'])

    def test_reader_splits_minimum_api_layout_from_wide_behavior_without_source_browser_tests(self):
        plan = self.select(MAIN + 'ui/book/reader/ReaderScreen.kt')
        self.assertEqual('reader', plan['scopes'])
        self.assertTrue(plan['device-24'])
        self.assertTrue(plan['device-35'])
        self.assertFalse(plan['minified'])
        for api in (24, 35):
            main, wide = ci_scope.device_classes({'reader'}, api)
            self.assertNotIn('NativeBrowser', main)
            self.assertNotIn('IsolatedExecution', main)
            if api == 35:
                self.assertIn('ReaderPositionInstrumentedTest', wide)
            else:
                self.assertEqual('', wide)
        self.assertEqual('', ci_scope.device_classes({'reader'}, 35)[0])
        self.assertIn('ReaderLayoutInstrumentedTest', ci_scope.device_classes({'reader'}, 24)[0])

    def test_speech_includes_reader_and_only_speech_minified_smoke(self):
        for path in ('tts/ReadAloudSession.kt', 'tts/ReadAloudController.kt'):
            plan = self.select(MAIN + path)
            self.assertEqual('reader,speech', plan['scopes'])
            self.assertIn(':source-speech:test', plan['gradle-args'])
            self.assertEqual(ci_scope.MINIFIED_SPEECH, plan['minified-classes'])
            self.assertIn('SystemSpeechInstrumentedTest',
                          ci_scope.device_classes({'reader', 'speech'}, 24)[0])
            self.assertIn('SpeechReplacementInstrumentedTest',
                          ci_scope.device_classes({'reader', 'speech'}, 24)[0])

    def test_speech_ui_keeps_app_consumers_and_minified_smoke_without_debug_devices(self):
        plan = self.select(MAIN + 'ui/tts/ReadAloudScreen.kt')
        self.assertEqual(':app:testDebugUnitTest -PciTestScope=speech-ui', plan['gradle-args'])
        self.assertEqual(ci_scope.MINIFIED_SPEECH, plan['minified-classes'])
        self.assertFalse(plan['device-24'])
        self.assertFalse(plan['device-35'])
        mixed = self.select(MAIN + 'ui/tts/SpeechControls.kt', MAIN + 'tts/ReadAloudSession.kt')
        self.assertTrue(mixed['device-24'])
        self.assertTrue(mixed['device-35'])
        self.assertIn(':source-speech:test', mixed['gradle-args'])

    def test_ci_helpers_only_run_scripts_and_mixed_unknown_inputs_stay_full(self):
        plan = self.select('.github/scripts/ci_cache_retention.py', '.github/scripts/test_ci_cache.py')
        self.assertEqual('ci', plan['scopes'])
        self.assertTrue(plan['scripts'])
        for check in ('jvm', 'lint', 'release', 'device-24', 'device-35', 'minified'):
            self.assertFalse(plan[check])
        mixed = self.select('.github/scripts/test_check_resources.py', MAIN + 'ui/tts/ReadAloudScreen.kt')
        self.assertTrue(mixed['scripts'])
        self.assertTrue(mixed['jvm'])
        self.assertTrue(mixed['minified'])
        self.assertTrue(mixed['lint'])
        self.assertEqual(ci_scope.plan({'full'}), self.select('.github/scripts/test_ci_cache.py',
                                                           '.github/scripts/unknown.py'))

    def test_mixed_changes_union_scopes(self):
        plan = self.select(MAIN + 'ui/home/bookshelf/home/BookshelfScreen.kt',
                           MAIN + 'ui/book/reader/ReaderScreen.kt', 'README.md')
        self.assertEqual('bookshelf,reader', plan['scopes'])
        self.assertIn('-PciTestScope=bookshelf,reader', plan['gradle-args'])
        self.assertTrue(plan['device-35'])

    def test_source_modules_keep_transitive_consumers_without_epub(self):
        for module, consumers in (
                ('network', {'network', 'import', 'execution', 'content', 'speech'}),
                ('rhino', {'rhino', 'execution', 'content', 'speech'}),
                ('import', {'import', 'content'})):
            with self.subTest(module=module):
                plan = self.select(f'sources/{module}/src/main/kotlin/Changed.kt')
                expected = {':app:testDebugUnitTest', ':source-compatibility:test'}
                expected.update(f':source-{name}:test' for name in consumers)
                self.assertEqual(expected, set(plan['gradle-args'].split()))
                self.assertTrue(plan['release'])
                self.assertTrue(plan['lint'])
                for api in (24, 35):
                    self.assertEqual(ci_scope.device_classes({'full'}, api),
                                     ci_scope.device_classes(set(plan['device-scopes'].split(',')), api))
                smoke = {ci_scope.MINIFIED_SOURCE}
                if 'speech' in consumers:
                    smoke.add(ci_scope.MINIFIED_SPEECH)
                self.assertEqual(smoke, set(plan['minified-classes'].split(',')))

    def test_source_impact_table_covers_actual_transitive_module_dependencies(self):
        dependencies = {module: set(re.findall(r'project\(":source-([a-z]+)"\)',
                        (ROOT / f'sources/{module}/build.gradle.kts').read_text(encoding='utf-8')))
                        for module in ci_scope.SOURCE_CONSUMERS}
        for changed, consumers in ci_scope.SOURCE_CONSUMERS.items():
            affected = {changed}
            while True:
                expanded = affected | {module for module, inputs in dependencies.items() if inputs & affected}
                if expanded == affected:
                    break
                affected = expanded
            self.assertEqual(affected, set(consumers), changed)

    def test_source_catalog_and_shared_test_inputs_union_their_consumers(self):
        plan = self.select('app/src/main/assets/source-catalog/Adult.json',
                           'sources/rhino/src/test/resources/fixtures/plain.ttf',
                           ci_scope.KOTLIN_ROOTS[1] + 'ui/home/bookshelf/NewShelfTest.kt')
        self.assertEqual({':app:testDebugUnitTest', ':source-content:test', ':source-rhino:test',
                          ':source-compatibility:test'}, set(plan['gradle-args'].split()))
        self.assertIn('full', plan['device-scopes'].split(','))
        self.assertTrue(plan['release'])
        self.assertEqual(ci_scope.MINIFIED_SOURCE, plan['minified-classes'])
        plan = self.select('sources/content/src/main/kotlin/hnovel/content/RuleSource.kt',
                           MAIN + 'data/export/ExportBookToEpubUseCase.kt')
        self.assertIn(':epub:test', plan['gradle-args'])
        self.assertNotIn('-PciTestScope', plan['gradle-args'])
        self.assertEqual(len(plan['gradle-args'].split()), len(set(plan['gradle-args'].split())))
        for path in ('sources/execution/src/main/aidl/Worker.aidl',
                     'sources/network/src/main/AndroidManifest.xml',
                     'sources/rhino/src/main/resources/script.js',
                     'sources/new-module/src/main/kotlin/Unknown.kt',
                     'app/src/main/assets/source-catalog/NewCategory.json'):
            with self.subTest(path=path):
                self.assertEqual(ci_scope.plan({'full'}), self.select(
                    'sources/content/src/main/kotlin/hnovel/content/RuleSource.kt', path))

    def test_new_ui_tests_without_package_filters_and_shared_inputs_keep_app_coverage(self):
        for relative in ('ui/home/settings/about/NewAboutTest.kt',
                         'ui/home/settings/licenses/NewLicenseTest.kt',
                         'ui/home/settings/logcat/NewLogTest.kt',
                         'ui/home/settings/textformatting/NewFormattingTest.kt',
                         'ui/home/settings/NewSharedSettingsTest.kt',
                         'ui/bookmanager/NewBookManagerTest.kt',
                         'ui/home/bookshelf/NewBookshelfTest.kt',
                         'ui/bangumi/NewBangumiTest.kt'):
            plan = self.select(ci_scope.KOTLIN_ROOTS[1] + relative)
            self.assertNotIn('-PciTestScope', plan['gradle-args'])
            self.assertFalse(plan['release'])
        for relative in ('ui/components/Controls.kt', 'ui/home/settings/theme/ThemeScreen.kt'):
            self.assertEqual(ci_scope.plan({'full'}), self.select(MAIN + relative))
        mixed = self.select(MAIN + 'ui/home/bookshelf/BookshelfScreen.kt',
                            MAIN + 'data/backup/BackupArchive.kt', MAIN + 'tts/ReadAloudSession.kt')
        self.assertIn('-PciTestScope=backup,bookshelf,reader,speech', mixed['gradle-args'])
        self.assertEqual(ci_scope.MINIFIED_SPEECH, mixed['minified-classes'])

    def test_shared_unknown_resources_and_build_changes_run_everything(self):
        for path in (MAIN + 'ui/components/Cards.kt', MAIN + 'ui/navigation/Navigation.kt',
                     MAIN + 'ui/home/settings/list/ReadingSettingsList.kt',
                     MAIN + 'data/local/UserReadingDataRepository.kt',
                     MAIN + 'data/download/BookDownloadStore.kt',
                     MAIN + 'data/localbook/LocalBookStore.kt',
                     MAIN + 'data/content/ContentJsonDecoder.kt',
                     MAIN + 'data/format/FormattingRuleProcessor.kt',
                     MAIN + 'data/logging/LoggerRepository.kt',
                     MAIN + 'ui/newfeature/NewScreen.kt',
                     'app/src/main/res/values/strings.xml', 'app/src/main/AndroidManifest.xml',
                     'sources/content/src/main/resources/unknown.json', 'gradle/libs.versions.toml',
                     'app/build.gradle.kts', '.github/scripts/ci_scope.py',
                     '.github/workflows/device-tests.yml', '.github/actions/ci-scope/action.yml'):
            with self.subTest(path=path):
                plan = self.select(path, MAIN + 'ui/home/bookshelf/home/BookshelfScreen.kt')
                self.assertEqual(ci_scope.plan({'full'}), plan)

    def test_non_runtime_allowlist_does_not_hide_inputs_or_moved_documents(self):
        for path in ('app/src/main/assets/source-catalog/README.md',
                     'docs/build.py', '.github/unknown.yml',
                     '.github/ISSUE_TEMPLATE/unknown.yml',
                     '.github/workflows/lint.yml'):
            with self.subTest(path=path):
                self.assertEqual(ci_scope.plan({'full'}), self.select('docs/backup.md', path))
        self.assertEqual('reader', self.select(
            '.github/ISSUE_TEMPLATE/bug_report.yml',
            MAIN + 'ui/book/reader/ReaderScreen.kt')['scopes'])

    def test_jvm_only_inputs_keep_owner_and_integrity_without_release_or_devices(self):
        test_root = ci_scope.KOTLIN_ROOTS[1]
        cases = [
                (test_root + 'TestSupport.kt', ':app:testDebugUnitTest', None),
                (test_root + 'data/content/ContentTestHost.kt', ':app:testDebugUnitTest', None),
                ('app/src/test/resources/backups/README.md', ':app:testDebugUnitTest', 'backup'),
                ('sources/network/src/test/kotlin/NetworkTest.kt', ':source-network:test', None)]
        cases += [(test_root + relative, ':app:testDebugUnitTest', scope) for relative, scope in (
            ('data/download/BookDownloadTest.kt', 'storage'),
            ('data/localbook/TxtBookParserTest.kt', 'storage'),
            ('ui/localbook/LocalBookImportViewModelTest.kt', 'storage'),
            ('ui/book/download/BookDownloadViewModelTest.kt', 'storage'),
            ('data/content/ContentDecodingContractTest.kt', 'reader'),
            ('data/format/FormatRepositoryTest.kt', 'reader'),
            ('ui/book/detail/DetailDirectoryPagingTest.kt', 'reader'))]
        for path, owner, scope in cases:
            with self.subTest(path=path):
                plan = self.select(path)
                self.assertIn(owner, plan['gradle-args'])
                self.assertIn(':source-compatibility:test', plan['gradle-args'])
                filters = [arg for arg in plan['gradle-args'].split() if arg.startswith('-PciTestScope=')]
                self.assertEqual([] if scope is None else ['-PciTestScope=' + scope], filters)
                self.assertFalse(plan['lint'])
                for check in ('release', 'device-24', 'device-35', 'minified'):
                    self.assertFalse(plan[check])

    def test_device_and_minified_test_inputs_keep_their_execution_environment(self):
        plan = self.select('app/src/androidTest/kotlin/indi/renakoni/nextvol/reader/ReaderLayoutInstrumentedTest.kt')
        self.assertTrue(plan['device-24'])
        self.assertTrue(plan['device-35'])
        self.assertEqual(':source-compatibility:test', plan['gradle-args'])
        self.assertFalse(plan['release'])
        self.assertFalse(plan['lint'])
        plan = self.select('tests/benchmark/src/main/kotlin/' + ci_scope.MINIFIED_SPEECH.replace('.', '/') + '.kt')
        self.assertEqual(ci_scope.MINIFIED_SPEECH, plan['minified-classes'])
        self.assertTrue(plan['minified'])
        self.assertFalse(plan['release'])
        self.assertFalse(plan['jvm'])
        self.assertFalse(plan['lint'])
        nav_plan = self.select('tests/benchmark/src/main/kotlin/' + ci_scope.MINIFIED_NAVIGATION.replace('.', '/') + '.kt')
        self.assertEqual(ci_scope.MINIFIED_NAVIGATION, nav_plan['minified-classes'])
        self.assertTrue(nav_plan['minified'])
        self.assertFalse(nav_plan['release'])
        self.assertFalse(nav_plan['jvm'])
        self.assertFalse(nav_plan['lint'])
        self.assertIn(ci_scope.MINIFIED_NAVIGATION, ci_scope.plan({'full'})['minified-classes'].split(','))

    def test_shared_test_inputs_include_jvm_and_android_consumers(self):
        for path, owner in (('sources/content/src/testFixtures/kotlin/RuleSourceFixture.kt', ':app:testDebugUnitTest'),
                            ('sources/rhino/src/test/resources/font.ttf', ':source-rhino:test'),
                            ('tests/source-compatibility/browser/hlib-native.json', ':source-content:test')):
            with self.subTest(path=path):
                plan = self.select(path)
                self.assertIn(owner, plan['gradle-args'])
                self.assertTrue(plan['device-24'])
                self.assertTrue(plan['device-35'])
                self.assertFalse(plan['release'])
        plan = self.select('tests/source-compatibility/fixtures/native-routing/site/index.html')
        self.assertTrue(plan['device-35'])
        self.assertFalse(plan['jvm'])

    def test_mixed_test_and_production_changes_union_without_widening_devices(self):
        plan = self.select('app/src/test/resources/source-updates/README.md', MAIN + 'ui/book/reader/ReaderScreen.kt')
        self.assertTrue(plan['release'])
        self.assertNotIn('-PciTestScope', plan['gradle-args'])
        self.assertEqual('reader', plan['device-scopes'])
        plan = self.select('sources/network/src/test/kotlin/DeletedTest.kt', 'docs/backup.md')
        self.assertIn(':source-compatibility:test', plan['gradle-args'])
        self.assertFalse(plan['release'])

    def test_cli_writes_action_outputs_and_skip_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            output, summary = Path(folder) / 'output', Path(folder) / 'summary'
            env = dict(os.environ, GITHUB_OUTPUT=str(output), GITHUB_STEP_SUMMARY=str(summary))
            result = subprocess.run([sys.executable, str(ROOT / '.github/scripts/ci_scope.py'),
                                     '--check', 'device-24', '--paths',
                                     MAIN + 'ui/home/bookshelf/home/BookshelfScreen.kt'],
                                    env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            values = dict(line.split('=', 1) for line in output.read_text().splitlines())
            self.assertEqual('false', values['run'])
            self.assertEqual('true', values['release'])
            self.assertEqual('bookshelf', values['scopes'])
            self.assertIn('device-24 | False', summary.read_text())

    def test_full_device_class_inventory_contains_existing_unique_classes(self):
        for api in (24, 35):
            main, wide = ci_scope.device_classes({'full'}, api)
            self.assertTrue(main)
            self.assertEqual(api == 35, bool(wide))
            classes = list(filter(None, (main + ',' + wide).split(',')))
            self.assertEqual(len(classes), len(set(classes)))
            for name in classes:
                self.assertTrue((ROOT / 'app/src/androidTest/kotlin' /
                                 (name.replace('.', '/') + '.kt')).is_file(), name)

    def test_manual_missing_event_and_failed_diff_fall_back_to_full(self):
        with tempfile.TemporaryDirectory() as folder:
            event = Path(folder) / 'event.json'
            event.write_text('{}', encoding='utf-8')
            for name in ('workflow_dispatch', 'pull_request', 'merge_group'):
                with patch.dict(os.environ, GITHUB_EVENT_PATH=str(event), GITHUB_EVENT_NAME=name):
                    plan, reason = ci_scope.github_plan()
                    self.assertEqual(ci_scope.plan({'full'}), plan)
                    self.assertIn('Full checks:', reason)
            with patch.dict(os.environ, GITHUB_EVENT_PATH=str(event), GITHUB_EVENT_NAME='pull_request'):
                with patch.object(ci_scope, 'changed_paths', side_effect=subprocess.CalledProcessError(1, 'git')):
                    self.assertEqual(ci_scope.plan({'full'}), ci_scope.github_plan()[0])

    def test_failed_base_fetch_falls_back_to_full(self):
        with tempfile.TemporaryDirectory() as folder:
            event = Path(folder) / 'event.json'
            event.write_text(json.dumps({'pull_request': {'base': {'sha': 'a' * 40}}}), encoding='utf-8')
            with patch.dict(os.environ, GITHUB_EVENT_PATH=str(event), GITHUB_EVENT_NAME='pull_request'):
                with patch.object(ci_scope.subprocess, 'run', side_effect=[
                    subprocess.CompletedProcess([], 1), subprocess.TimeoutExpired('git fetch', 120)
                ]):
                    self.assertEqual(ci_scope.plan({'full'}), ci_scope.github_plan()[0])

    def test_real_git_diff_keeps_deleted_renamed_and_more_than_300_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            def git(*args):
                return subprocess.check_output(['git', '-C', folder, *args], stderr=subprocess.DEVNULL).decode().strip()

            git('init')
            git('config', 'user.email', 'ci-test@example.invalid')
            git('config', 'user.name', 'CI scope test')
            root = Path(folder)
            old = root / (MAIN + 'ui/book/reader/ReaderScreen.kt')
            old.parent.mkdir(parents=True)
            old.write_text('reader', encoding='utf-8')
            (root / 'deleted.txt').touch()
            git('add', '.')
            git('commit', '-m', 'base')
            base = git('rev-parse', 'HEAD')
            new = root / (MAIN + 'ui/home/bookshelf/FormerReader.kt')
            new.parent.mkdir(parents=True)
            old.rename(new)
            (root / 'deleted.txt').unlink()
            for index in range(305):
                (root / f'doc-{index}.md').touch()
            git('add', '-A')
            git('commit', '-m', 'move and delete')
            previous = Path.cwd()
            try:
                os.chdir(folder)
                for event_name, event in (
                    ('pull_request', {'pull_request': {'base': {'sha': base}}}),
                    ('merge_group', {'merge_group': {'base_sha': base}}),
                    ('push', {'before': base}),
                ):
                    paths = ci_scope.changed_paths(event_name, event)
                    self.assertEqual(308, len(paths))
                    self.assertIn(old.relative_to(root).as_posix(), paths)
                    self.assertIn(new.relative_to(root).as_posix(), paths)
                    self.assertIn('deleted.txt', paths)
                    self.assertEqual({'full'}, ci_scope.scopes_for_paths(paths))
                    self.assertEqual({'reader', 'bookshelf'}, ci_scope.scopes_for_paths(
                        [path for path in paths if path != 'deleted.txt']))
                shallow = root / 'shallow'
                subprocess.run(['git', 'clone', '--depth=1', root.as_uri(), str(shallow)],
                               check=True, capture_output=True)
                os.chdir(shallow)
                self.assertNotEqual(0, subprocess.run(['git', 'cat-file', '-e', base],
                                                     capture_output=True).returncode)
                self.assertEqual(set(paths), set(ci_scope.changed_paths(
                    'pull_request', {'pull_request': {'base': {'sha': base}}})))
            finally:
                os.chdir(previous)


@unittest.skipIf(os.name == 'nt', 'Shell execution requires Linux, as used by CI')
class ConnectedSelectionTests(unittest.TestCase):
    def run_connected(self, api, scopes, failure=''):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            scripts = root / '.github/scripts'
            scripts.mkdir(parents=True)
            for name in ('connected-tests.sh', 'ci_scope.py'):
                shutil.copyfile(ROOT / '.github/scripts' / name, scripts / name)
            (scripts / 'run-prebuilt-tests.sh').write_text('''echo "$4" >> calls
if [[ "$FAILURE" == narrow && "$3" != */reader ]]; then exit 23; fi
if [[ "$FAILURE" == wide && "$3" == */reader ]]; then exit 24; fi
''')
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env bash
echo "$*" >> adb-calls
case "$*" in
  'shell getprop ro.build.version.sdk') echo "$API" ;;
  'shell wm size') echo 'Physical size: 2560x1440' ;;
  'shell wm density') echo 'Physical density: 160' ;;
esac
''')
            adb.chmod(0o755)
            gradle = root / 'gradlew'
            gradle.write_text('#!/usr/bin/env bash\necho "$*" >> calls\n')
            gradle.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       API=str(api), CI_TEST_SCOPES=scopes, FAILURE=failure)
            result = subprocess.run(['bash', str(scripts / 'connected-tests.sh')],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=15)
            calls = (root / 'calls').read_text().splitlines() if (root / 'calls').exists() else []
            return result, calls, (root / 'adb-calls').read_text()

    def test_api35_reader_runs_only_wide_suite_and_restores_viewport(self):
        result, calls, adb = self.run_connected(35, 'reader')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([ci_scope.device_classes({'reader'}, 35)[1]], calls)
        self.assertIn('shell wm size reset', adb)
        self.assertIn('shell wm density reset', adb)

    def test_api24_reader_runs_layout_suite_without_repeating_wide_tests(self):
        result, calls, adb = self.run_connected(24, 'reader')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([ci_scope.device_classes({'reader'}, 24)[0]], calls)
        self.assertNotIn('shell wm size', adb)

    def test_full_retains_both_api_suites(self):
        for api in (24, 35):
            result, calls, _ = self.run_connected(api, 'full')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(list(filter(None, ci_scope.device_classes({'full'}, api))), calls)

    def test_test_failures_propagate_and_wide_failure_restores_viewport(self):
        result, calls, _ = self.run_connected(24, 'reader', 'narrow')
        self.assertEqual(23, result.returncode)
        self.assertEqual(1, len(calls))
        result, _, adb = self.run_connected(35, 'reader', 'wide')
        self.assertEqual(24, result.returncode)
        self.assertIn('shell wm size reset', adb)

    def test_empty_selection_cannot_accidentally_run_all_instrumentation(self):
        result, calls, _ = self.run_connected(24, 'ui')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
