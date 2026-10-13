import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import ci_cache
import ci_scope


class CacheSelectionTests(unittest.TestCase):
    def test_full_warmup_compiles_jvm_tests_and_apks_without_running_tests_or_r8(self):
        tasks = ci_cache.full_plan()['gradle-args'].split()
        self.assertIn(':epub:testClasses', tasks)
        self.assertIn(':source-content:testClasses', tasks)
        self.assertIn(':source-speech:testClasses', tasks)
        self.assertIn(':app:compileDebugUnitTestKotlin', tasks)
        self.assertIn(':app:assembleDebugAndroidTest', tasks)
        self.assertIn(':benchmark:assembleBenchmark', tasks)
        self.assertFalse(set(tasks) & set(ci_scope.JVM_TASKS))
        self.assertNotIn(':app:assembleBenchmark', tasks)
        self.assertNotIn(':app:assembleRelease', tasks)

    def test_docs_and_templates_do_not_prepare_android(self):
        plan = ci_cache.plan_for_paths(['README.md', '.github/PULL_REQUEST_TEMPLATE.md',
                                        '.github/ISSUE_TEMPLATE/maintenance_task.yml'])
        self.assertEqual({'gradle-args': '', 'images': False}, plan)

    def test_module_test_changes_keep_reviewed_owner_and_compatibility_compilation(self):
        plan = ci_cache.plan_for_paths(['sources/network/src/test/kotlin/NetworkTest.kt'])
        self.assertEqual({':source-network:testClasses', ':source-compatibility:testClasses'},
                         set(plan['gradle-args'].split()))
        self.assertFalse(plan['images'])

    def test_app_test_changes_do_not_build_device_apks(self):
        plan = ci_cache.plan_for_paths([
            'app/src/test/kotlin/indi/renakoni/nextvol/ui/home/bookshelf/BookshelfTest.kt'])
        tasks = plan['gradle-args'].split()
        self.assertIn(':app:compileDebugUnitTestKotlin', tasks)
        self.assertNotIn(':app:assembleDebug', tasks)
        self.assertFalse(any(task.startswith('-P') for task in tasks))
        self.assertFalse(plan['images'])

    def test_source_consumers_use_shared_selection_without_image_jobs(self):
        plan = ci_cache.plan_for_paths(['sources/network/src/main/kotlin/Network.kt'])
        tasks = plan['gradle-args'].split()
        for module in ('network', 'import', 'execution', 'content', 'speech'):
            self.assertIn(f':source-{module}:testClasses', tasks)
        self.assertIn(':app:compileDebugUnitTestKotlin', tasks)
        self.assertIn(':app:assembleDebug', tasks)
        self.assertNotIn(':epub:testClasses', tasks)
        self.assertFalse(plan['images'])

    def test_only_sdk_inputs_or_manual_runs_select_images(self):
        self.assertTrue(ci_cache.plan_for_paths(['.github/scripts/prepare-android-sdk.sh'])['images'])
        self.assertFalse(ci_cache.plan_for_paths(['gradle/libs.versions.toml'])['images'])
        with patch.dict(os.environ, GITHUB_EVENT_NAME='workflow_dispatch'):
            self.assertEqual(ci_cache.full_plan(), ci_cache.github_plan()[0])

    def test_unmapped_task_fails_instead_of_executing_tests_during_warmup(self):
        checks = dict(ci_scope.plan({'full'}), **{'gradle-args': ':app:connectedDebugAndroidTest'})
        with self.assertRaisesRegex(ValueError, 'No compilation-only mapping'):
            ci_cache.compilation_tasks(checks)

    def test_unavailable_push_diff_falls_back_to_full_cache_warmup(self):
        with tempfile.TemporaryDirectory() as folder:
            event = Path(folder) / 'event.json'
            event.write_text(json.dumps({'before': 'a' * 40}), encoding='utf-8')
            with patch.dict(os.environ, GITHUB_EVENT_NAME='push', GITHUB_EVENT_PATH=str(event)), \
                    patch.object(ci_scope, 'changed_paths', side_effect=subprocess.CalledProcessError(1, 'git')):
                plan, reason = ci_cache.github_plan()
            self.assertEqual(ci_cache.full_plan(), plan)
            self.assertIn('Unavailable push diff', reason)


if __name__ == '__main__':
    unittest.main()
