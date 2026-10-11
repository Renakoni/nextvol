import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent


class CiEmulatorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='static-emulator-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = self.root / 'sdk'
        self.env = dict(os.environ, ANDROID_HOME=str(self.sdk),
                        ANDROID_AVD_HOME=str(self.root / 'avd'), CASE_ROOT=str(self.root))
        # Mock only host KVM access; real file checks, process handling, and exit
        # status propagation still execute in the production shell script.
        bash_env = self.root / 'bash-env'
        bash_env.write_text('''test() {
  if [[ "$2" == /dev/kvm ]]; then return 0; fi
  builtin test "$@"
}
if [[ "$CASE" == boot-timeout ]]; then
  sleep() { SECONDS=$((SECONDS + 181)); }
fi
''')
        self.env['BASH_ENV'] = str(bash_env)
        self.script('emulator/emulator', '''echo "$*" > "$CASE_ROOT/emulator-args"
echo $$ > "$CASE_ROOT/emulator-pid"
echo launch >> "$CASE_ROOT/launches"
if [[ "$CASE" == boot-crash ]]; then exit 27; fi
if [[ "$CASE" == boot-recovers && $(wc -l < "$CASE_ROOT/launches") == 1 ]]; then exit 27; fi
exec sleep 60
''')
        self.script('platform-tools/adb', '''echo "$*" >> "$CASE_ROOT/adb-calls"
case "$*" in
  'start-server')
    if [[ "$CASE" == adb-recovers && ! -f "$CASE_ROOT/adb-restarted" ]]; then
      touch "$CASE_ROOT/adb-restarted"
      exit 17
    fi ;;
  'shell pm path android') echo package:/system/framework/framework-res.apk ;;
  'shell getprop sys.boot_completed')
    if [[ "$CASE" == boot-crash || "$CASE" == boot-timeout ]]; then exit 1; fi
    if [[ "$CASE" == boot-recovers ]] && [[ ! -f "$CASE_ROOT/launches" || $(wc -l < "$CASE_ROOT/launches") != 2 ]]; then exit 1; fi
    echo 1 ;;
  'shell wm size 320x640')
    if [[ "$CASE" == prepare-timeout ]]; then exit 124; fi
    if [[ "$CASE" == prepare-recovers && ! -f "$CASE_ROOT/prepare-retried" ]]; then
      touch "$CASE_ROOT/prepare-retried"
      exit 124
    fi ;;
  'shell settings put global '* )
    if [[ "$CASE" == settings-fail ]]; then exit 31; fi ;;
  'emu kill')
    if [[ "$CASE" == cleanup-fail ]]; then exit 29; fi ;;
esac
''')
        image = self.sdk / 'system-images/android-35/google_apis/x86_64/system.img'
        image.parent.mkdir(parents=True)
        image.touch()
        command = self.root / 'command'
        command.write_text('''#!/usr/bin/env bash
echo "$*" >> "$CASE_ROOT/ran"
exit "$TEST_EXIT"
''')
        command.chmod(0o755)

    def script(self, name, body):
        path = self.sdk / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#!/usr/bin/env bash\nset -eu\n' + body)
        path.chmod(0o755)

    def run_case(self, case='ready', status=0, api='35'):
        return subprocess.run(['bash', str(SCRIPTS / 'run-static-emulator.sh'), api,
                               str(self.root / 'command'), 'one argument'],
                              cwd=self.root, env=dict(self.env, CASE=case, TEST_EXIT=str(status)),
                              capture_output=True, text=True, timeout=15)

    def test_ready_device_runs_original_command_and_stops_emulator(self):
        result = self.run_case()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('one argument\n', (self.root / 'ran').read_text())
        self.assertIn('-no-snapshot', (self.root / 'emulator-args').read_text())
        self.assertIn('emu kill', (self.root / 'adb-calls').read_text())
        pid = int((self.root / 'emulator-pid').read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_test_failure_survives_shutdown_failure(self):
        result = self.run_case('cleanup-fail', status=42)
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertEqual(['launch'], (self.root / 'launches').read_text().splitlines())
        self.assertEqual(['one argument'], (self.root / 'ran').read_text().splitlines())

    def test_crashed_emulator_does_not_start_tests(self):
        result = self.run_case('boot-crash')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('exited before Android boot', result.stderr)
        self.assertFalse((self.root / 'ran').exists())
        self.assertEqual(2, len((self.root / 'launches').read_text().splitlines()))

    def test_failed_first_boot_restarts_once_before_running_tests(self):
        result = self.run_case('boot-recovers')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len((self.root / 'launches').read_text().splitlines()))
        self.assertEqual(['one argument'], (self.root / 'ran').read_text().splitlines())
        self.assertIn('-wipe-data', (self.root / 'emulator-args').read_text())

    def test_boot_timeout_is_bounded_and_never_starts_tests(self):
        result = self.run_case('boot-timeout')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('two attempts', result.stderr)
        self.assertFalse((self.root / 'ran').exists())
        # The mocked clock can end a window before the background emulator records its
        # launch, so count the synchronous per-attempt ADB start instead.
        self.assertEqual(2, (self.root / 'adb-calls').read_text().splitlines().count('start-server'))

    def test_failed_adb_start_retries_before_launching_the_emulator(self):
        result = self.run_case('adb-recovers')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, (self.root / 'adb-calls').read_text().splitlines().count('start-server'))
        self.assertEqual(['launch'], (self.root / 'launches').read_text().splitlines())
        self.assertEqual(['one argument'], (self.root / 'ran').read_text().splitlines())

    def test_readiness_failure_stops_before_tests(self):
        result = self.run_case('settings-fail')
        self.assertEqual(31, result.returncode, result.stderr)
        self.assertFalse((self.root / 'ran').exists())

    def test_viewport_timeout_restarts_before_running_tests_once(self):
        result = self.run_case('prepare-recovers')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len((self.root / 'launches').read_text().splitlines()))
        self.assertEqual(['one argument'], (self.root / 'ran').read_text().splitlines())

    def test_persistent_viewport_timeout_never_runs_tests_or_later_setup(self):
        result = self.run_case('prepare-timeout')
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertFalse((self.root / 'ran').exists())
        calls = (self.root / 'adb-calls').read_text().splitlines()
        self.assertEqual(2, calls.count('start-server'))
        self.assertFalse(any('settings put' in call for call in calls))

    def test_missing_image_never_launches_emulator(self):
        result = self.run_case(api='24')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Missing emulator resource', result.stderr)
        self.assertFalse((self.root / 'emulator-args').exists())


if __name__ == '__main__':
    unittest.main()
