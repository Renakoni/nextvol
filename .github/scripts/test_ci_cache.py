import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import ci_cache
import ci_cache_retention
import ci_scope
import ci_task_cache


class CacheSelectionTests(unittest.TestCase):
    def test_variant_budget_keeps_newer_fitting_payloads_and_leaves_metadata_alone(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            # The newest oversized entry must not crowd out useful smaller ones.
            for identifier, size in ((1, 6), (2, 4), (3, 6), (4, 11)):
                entry = directory / f'{identifier:032x}'
                entry.write_bytes(b'x' * size)
                os.utime(entry, (identifier, identifier))
            metadata = directory / 'gc.properties'
            metadata.write_text('metadata', encoding='utf-8')

            self.assertEqual((27, 10), ci_task_cache.trim_cache(directory, limit=10))
            self.assertEqual({'gc.properties', f'{2:032x}', f'{3:032x}'},
                             {path.name for path in directory.iterdir()})
            self.assertEqual('metadata', metadata.read_text(encoding='utf-8'))
            # A bounded snapshot is unchanged on the next save, including hits.
            self.assertEqual((10, 10), ci_task_cache.trim_cache(directory, limit=10))

    def test_native_cache_retention_keeps_the_latest_inputs_per_ref_and_toolchain(self):
        def entry(identifier, ref, toolchain, inputs='a'):
            return {'id': identifier, 'ref': ref, 'created_at': f'2026-10-{identifier:02d}',
                    'key': f'onnxruntime-linux-{toolchain}-{inputs * 64}'}

        current = f'v2-ubuntu24-{"c" * 64}'
        upgraded = f'v2-ubuntu24-{"d" * 64}'
        caches = [
            entry(1, 'refs/heads/main', current),
            entry(2, 'refs/heads/main', current, inputs='b'),
            entry(3, 'refs/heads/main', upgraded),
            entry(4, 'refs/pull/804/merge', current),
            entry(5, 'refs/pull/804/merge', current, inputs='b'),
            entry(6, 'refs/pull/805/merge', current),
        ]
        self.assertEqual({1, 4}, {cache['id'] for cache in ci_cache_retention.obsolete_snapshots(caches)})

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

    def test_module_test_changes_keep_reviewed_owner_and_compatibility_compilation(self):
        plan = ci_cache.plan_for_paths(['sources/network/src/test/kotlin/NetworkTest.kt'])
        self.assertEqual({':source-network:testClasses', ':source-compatibility:testClasses'},
                         set(plan['gradle-args'].split()))
        self.assertFalse(plan['images'])

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
