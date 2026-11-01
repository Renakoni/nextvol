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

    def test_app_update_keeps_its_regressions_without_source_or_device_work(self):
        plan = self.select(MAIN + 'data/update/UpdateCheckRepository.kt',
                           MAIN + 'data/update/UpdateArtifactDownloader.kt',
                           MAIN + 'ui/home/settings/updates/UpdatesSettingsScreen.kt',
                           MAIN + 'ui/dialog/UpdatesAvailableDialogViewModel.kt')
        self.assertEqual('update', plan['scopes'])
        self.assertEqual(':app:testDebugUnitTest -PciTestScope=update', plan['gradle-args'])
        self.assertTrue(plan['release'])
        self.assertTrue(plan['lint'])
        self.assertFalse(any(plan[check] for check in ('device-24', 'device-35', 'minified')))
        tests = self.select(ci_scope.KOTLIN_ROOTS[1] + 'data/update/UpdateDownloadRepositoryTest.kt')
        self.assertIn('-PciTestScope=update', tests['gradle-args'])
        self.assertFalse(tests['release'])
        unknown_ui_test = self.select(ci_scope.KOTLIN_ROOTS[1] +
                                      'ui/home/settings/updates/NewUpdateUiTest.kt')
        self.assertNotIn('-PciTestScope=', unknown_ui_test['gradle-args'])
        self.assertFalse(unknown_ui_test['release'])

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
        patterns = re.findall(r'"((?:ui|data|tts)\.[A-Za-z0-9.*]+)"', filters)
        classes = [ci_scope.PACKAGE + path.relative_to(ROOT / 'app/src/test/kotlin/indi/renakoni/nextvol')
                   .with_suffix('').as_posix().replace('/', '.')
                   for path in (ROOT / 'app/src/test/kotlin/indi/renakoni/nextvol').rglob('*.kt')
                   if '@Test' in path.read_text(encoding='utf-8')]
        self.assertTrue(patterns)
        for pattern in patterns:
            self.assertTrue(any(fnmatch.fnmatchcase(name, ci_scope.PACKAGE + pattern) for name in classes), pattern)
        self.assertNotIn('excludeTestsMatching', build)

    def test_export_selects_module_consumers_and_only_api24_sharing(self):
        plan = self.select(MAIN + 'data/export/ExportBookToEpubUseCase.kt',
                           MAIN + 'data/work/ExportBookToEPUBWork.kt',
                           MAIN + 'ui/book/detail/EpubShareActivity.kt',
                           'epub/src/main/kotlin/io/nightfish/potatoepub/builder/EpubBuilder.kt')
        self.assertEqual('export', plan['scopes'])
        self.assertEqual(':app:testDebugUnitTest :epub:test -PciTestScope=export', plan['gradle-args'])
        self.assertTrue(plan['release'])
        self.assertTrue(plan['lint'])
        self.assertEqual((ci_scope.PACKAGE + 'EpubShareInstrumentedTest', ''),
                         ci_scope.device_classes({'export'}, 24))
        self.assertFalse(plan['device-35'])
        self.assertFalse(plan['minified'])
        tests = self.select(ci_scope.KOTLIN_ROOTS[1] + 'data/work/SourceExportWorkerTest.kt')
        self.assertIn('-PciTestScope=export', tests['gradle-args'])
        self.assertFalse(tests['release'])
        self.assertFalse(tests['device-24'])
        device = self.select('app/src/androidTest/kotlin/indi/renakoni/nextvol/EpubShareInstrumentedTest.kt')
        self.assertTrue(device['device-24'])
        self.assertFalse(device['device-35'])
        self.assertFalse(device['release'])

    def test_export_unions_update_and_preserves_shared_boundaries(self):
        self.assertIn('-PciTestScope=export,update', self.select(
            MAIN + 'data/export/EpubShareFiles.kt', MAIN + 'data/update/UpdateSource.kt')['gradle-args'])
        for path in ('data/image/SourceImage.kt', 'data/localbook/EpubBookParser.kt',
                     'data/book/BookRepository.kt', 'ui/book/detail/DetailViewModel.kt'):
            self.assertEqual(ci_scope.plan({'full'}), self.select(
                MAIN + 'data/export/EpubShareFiles.kt', MAIN + path))
        for relative in ('ui/export/NewShareTest.kt', 'data/download/BookDownloadTest.kt',
                         'data/work/BookWorkIdentityTest.kt'):
            self.assertNotIn('-PciTestScope=', self.select(ci_scope.KOTLIN_ROOTS[1] + relative)['gradle-args'])

    def test_backup_production_and_legacy_resources_keep_their_consumers(self):
        plan = self.select(MAIN + 'data/backup/BackupDataManager.kt',
                           MAIN + 'data/work/SaveBookshelfWork.kt',
                           MAIN + 'ui/home/settings/ExportUserDataDialogViewModel.kt')
        self.assertEqual('backup', plan['scopes'])
        self.assertEqual(':app:testDebugUnitTest -PciTestScope=backup', plan['gradle-args'])
        self.assertTrue(plan['release'])
        self.assertTrue(plan['lint'])
        self.assertFalse(any(plan[check] for check in ('device-24', 'device-35', 'minified')))
        for resource in ('README.md', 'before-nextvol.lnr'):
            tests = self.select('app/src/test/resources/backups/' + resource)
            self.assertIn('-PciTestScope=backup', tests['gradle-args'])
            self.assertTrue(tests['lint'])
            self.assertFalse(tests['release'])
            self.assertFalse(tests['device-24'])

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
        for relative in ('data/local/SourceIdentityRoomTest.kt', 'data/localbook/LocalBookRelinkTest.kt'):
            tests = self.select(ci_scope.KOTLIN_ROOTS[1] + relative)
            self.assertNotIn('-PciTestScope=', tests['gradle-args'])
            self.assertFalse(tests['device-24'])

    def test_storage_keeps_download_import_and_reading_consumers(self):
        plan = self.select(MAIN + 'data/storage/ChapterOwnership.kt',
                           MAIN + 'data/storage/StorageBookQuery.kt',
                           MAIN + 'data/storage/StorageBookCleanup.kt',
                           MAIN + 'ui/bookmanager/BookManagerViewModel.kt')
        self.assertEqual('storage', plan['scopes'])
        self.assertEqual(':app:testDebugUnitTest -PciTestScope=storage', plan['gradle-args'])
        self.assertTrue(plan['release'])
        self.assertTrue(plan['lint'])
        main, wide = ci_scope.device_classes({'storage'}, 24)
        self.assertEqual(ci_scope.qualified([
            'data.download.DownloadPersistenceInstrumentedTest',
            'data.localbook.LocalBookImportInstrumentedTest']), main)
        self.assertEqual('', wide)
        self.assertFalse(plan['device-35'])
        self.assertFalse(plan['minified'])
        mixed = self.select(MAIN + 'data/storage/StorageBookCleanup.kt',
                            ci_scope.KOTLIN_ROOTS[1] + 'data/download/BookDownloadTest.kt',
                            ci_scope.KOTLIN_ROOTS[1] + 'data/localbook/LocalBookStoreTest.kt')
        self.assertNotIn('-PciTestScope=', mixed['gradle-args'])
        self.assertEqual('storage', mixed['device-scopes'])
        self.assertFalse(mixed['device-35'])

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
                self.assertEqual(':app:testDebugUnitTest -PciTestScope=' + scope, plan['gradle-args'])
                for check in ('jvm', 'lint', 'release'):
                    self.assertTrue(plan[check])
                for check in ('device-24', 'device-35', 'minified'):
                    self.assertFalse(plan[check])

    def test_reader_keeps_both_api_reflow_suites_without_source_browser_tests(self):
        plan = self.select(MAIN + 'ui/book/reader/ReaderScreen.kt')
        self.assertEqual('reader', plan['scopes'])
        self.assertTrue(plan['device-24'])
        self.assertTrue(plan['device-35'])
        self.assertFalse(plan['minified'])
        for api in (24, 35):
            main, wide = ci_scope.device_classes({'reader'}, api)
            self.assertNotIn('NativeBrowser', main)
            self.assertNotIn('IsolatedExecution', main)
            self.assertIn('ReaderPositionInstrumentedTest', wide)
            self.assertIn('ReaderSpreadInstrumentedTest', wide)
        self.assertEqual('', ci_scope.device_classes({'reader'}, 35)[0])
        self.assertIn('ReaderLayoutInstrumentedTest', ci_scope.device_classes({'reader'}, 24)[0])

    def test_speech_includes_reader_and_only_speech_minified_smoke(self):
        for path in ('tts/ReadAloudSession.kt', 'ui/tts/ReadAloudScreen.kt'):
            plan = self.select(MAIN + path)
            self.assertEqual('reader,speech', plan['scopes'])
            self.assertIn(':source-speech:test', plan['gradle-args'])
            self.assertEqual(ci_scope.MINIFIED_SPEECH, plan['minified-classes'])
            self.assertIn('SystemSpeechInstrumentedTest',
                          ci_scope.device_classes({'reader', 'speech'}, 24)[0])
            self.assertIn('SpeechReplacementInstrumentedTest',
                          ci_scope.device_classes({'reader', 'speech'}, 24)[0])

    def test_catalog_keeps_source_smoke_without_unrelated_device_suites(self):
        for path in ('ui/home/discovery/DiscoveryComponents.kt',
                     'ui/home/categories/CategoriesScreen.kt'):
            plan = self.select(MAIN + path)
            self.assertEqual('catalog', plan['scopes'])
            self.assertEqual(ci_scope.MINIFIED_SOURCE, plan['minified-classes'])
            self.assertIn('-PciTestScope=catalog', plan['gradle-args'])
            self.assertFalse(plan['device-24'])
            self.assertFalse(plan['device-35'])
        plan = self.select(MAIN + 'ui/home/categories/CategoriesScreen.kt', MAIN + 'tts/ReadAloudSession.kt')
        self.assertEqual(ci_scope.MINIFIED_SOURCE + ',' + ci_scope.MINIFIED_SPEECH, plan['minified-classes'])

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

    def test_source_speech_keeps_full_app_and_existing_speech_devices(self):
        plan = self.select('sources/speech/src/main/kotlin/hnovel/speech/HttpSpeechClient.kt')
        self.assertEqual({':app:testDebugUnitTest', ':source-speech:test', ':source-compatibility:test'},
                         set(plan['gradle-args'].split()))
        self.assertEqual('reader,speech', plan['device-scopes'])
        self.assertEqual(ci_scope.MINIFIED_SPEECH, plan['minified-classes'])

    def test_source_catalog_and_shared_test_inputs_union_their_consumers(self):
        plan = self.select('app/src/main/assets/source-catalog/Adult.json',
                           'sources/rhino/src/test/resources/fixtures/plain.ttf',
                           ci_scope.KOTLIN_ROOTS[1] + 'ui/home/bookshelf/BookshelfTest.kt')
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

    def test_new_settings_tests_and_shared_ui_inputs_keep_app_coverage(self):
        for relative in ('ui/home/settings/about/NewAboutTest.kt',
                         'ui/home/settings/licenses/NewLicenseTest.kt',
                         'ui/home/settings/logcat/NewLogTest.kt',
                         'ui/home/settings/SettingsHierarchyTest.kt'):
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
                     MAIN + 'ui/newfeature/NewScreen.kt',
                     'app/src/main/res/values/strings.xml', 'app/src/main/AndroidManifest.xml',
                     'sources/content/src/main/resources/unknown.json', 'gradle/libs.versions.toml',
                     'app/build.gradle.kts', '.github/scripts/ci_scope.py',
                     '.github/workflows/device-tests.yml', '.github/actions/ci-scope/action.yml'):
            with self.subTest(path=path):
                plan = self.select(path, MAIN + 'ui/home/bookshelf/home/BookshelfScreen.kt')
                self.assertEqual(ci_scope.plan({'full'}), plan)

    def test_documentation_only_skips_android_work(self):
        plan = self.select('README.md', 'AGENTS.md', '.github/CI.md',
                           'docs/backup.md', 'docs/releases/0.1.0.md',
                           '.github/PULL_REQUEST_TEMPLATE.md', '.github/FUNDING.yml',
                           '.github/ISSUE_TEMPLATE/bug_report.yml',
                           '.github/ISSUE_TEMPLATE/feature_request.yml',
                           '.github/ISSUE_TEMPLATE/maintenance_task.yml')
        self.assertEqual('docs', plan['scopes'])
        self.assertEqual('', plan['gradle-args'])
        for check in ('jvm', 'device-24', 'device-35', 'release', 'lint', 'minified'):
            self.assertFalse(plan[check])

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

    def test_test_changes_select_their_scope(self):
        self.assertEqual('reader', self.select(
            'app/src/test/kotlin/indi/renakoni/nextvol/ui/book/reader/ReaderLayoutTest.kt')['scopes'])
        self.assertEqual('reader', self.select(
            'app/src/androidTest/kotlin/indi/renakoni/nextvol/reader/ReaderLayoutInstrumentedTest.kt')['scopes'])
        self.assertEqual('reader,speech', self.select(
            'app/src/androidTest/kotlin/indi/renakoni/nextvol/tts/SystemSpeechInstrumentedTest.kt')['scopes'])

    def test_jvm_only_inputs_keep_owner_and_integrity_without_release_or_devices(self):
        for path, owner in ((MAIN.replace('/main/', '/test/') + 'TestSupport.kt', ':app:testDebugUnitTest'),
                            ('app/src/test/resources/backups/README.md', ':app:testDebugUnitTest'),
                            ('sources/network/src/test/kotlin/NetworkTest.kt', ':source-network:test')):
            with self.subTest(path=path):
                plan = self.select(path)
                self.assertIn(owner, plan['gradle-args'])
                self.assertIn(':source-compatibility:test', plan['gradle-args'])
                self.assertTrue(plan['lint'])
                for check in ('release', 'device-24', 'device-35', 'minified'):
                    self.assertFalse(plan[check])

    def test_device_and_minified_test_inputs_keep_their_execution_environment(self):
        plan = self.select('app/src/androidTest/kotlin/indi/renakoni/nextvol/reader/ReaderLayoutInstrumentedTest.kt')
        self.assertTrue(plan['device-24'])
        self.assertTrue(plan['device-35'])
        self.assertEqual(':source-compatibility:test', plan['gradle-args'])
        self.assertFalse(plan['release'])
        plan = self.select('tests/benchmark/src/main/kotlin/' + ci_scope.MINIFIED_SPEECH.replace('.', '/') + '.kt')
        self.assertEqual(ci_scope.MINIFIED_SPEECH, plan['minified-classes'])
        self.assertTrue(plan['minified'])
        self.assertFalse(plan['release'])
        self.assertFalse(plan['jvm'])

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

    def test_empty_diff_runs_full_checks(self):
        self.assertEqual(ci_scope.plan({'full'}), self.select())

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

    def test_full_device_class_inventory_exists_and_keeps_current_coverage(self):
        for api, count in ((24, 18), (35, 9)):
            main, wide = ci_scope.device_classes({'full'}, api)
            self.assertEqual(count, len(main.split(',')))
            self.assertEqual(2, len(wide.split(',')))
            classes = (main + ',' + wide).split(',')
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

    def test_api24_reader_runs_narrow_and_wide_suites(self):
        result, calls, _ = self.run_connected(24, 'reader')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(list(ci_scope.device_classes({'reader'}, 24)), calls)

    def test_full_retains_both_api_suites(self):
        for api in (24, 35):
            result, calls, _ = self.run_connected(api, 'full')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(list(ci_scope.device_classes({'full'}, api)), calls)

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

    def test_other_device_apis_retain_the_local_gradle_entry(self):
        result, calls, adb = self.run_connected(36, 'full')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(calls))
        self.assertIn(':app:connectedDebugAndroidTest', calls[0])
        self.assertNotIn('shell wm size', adb)


if __name__ == '__main__':
    unittest.main()
