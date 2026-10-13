"""Issue 721 controlled remote compilation experiment; removed after evaluation."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time

import ci_cache
import ci_scope

BASE = '7124552a26a0212c4f7e7f5212f3001471d85c11'
PREVIOUS = '75a9396fbf2b7a340760b3e2154deaddad8d905b'
ROOT = Path(os.environ['RUNNER_TEMP']) / 'cache-study'
REPORTS = ROOT / 'reports'
REPORTS.mkdir(parents=True, exist_ok=True)
CACHE = ROOT / 'task-cache'
os.environ['CI_GRADLE_BUILD_CACHE_DIR'] = str(CACHE)
CACHE.mkdir(parents=True, exist_ok=True)
GUARD = ROOT / 'compile-only.gradle'
GUARD.write_text('''gradle.taskGraph.whenReady { graph ->
    assert !graph.allTasks.any { it instanceof org.gradle.api.tasks.testing.Test ||
        it.name.startsWith('minify') || it.name.startsWith('connected') }
}
''')


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def paths(before):
    subprocess.run(['git', 'fetch', '--depth=1', 'origin', before], check=True)
    return git('diff', '--name-only', before, 'HEAD').splitlines()


def snapshot(name):
    with tarfile.open(ROOT / f'{name}.tar', 'w') as archive:
        for entry in sorted(CACHE.iterdir()):
            if entry.is_file() and re.fullmatch(r'[0-9a-f]{32}', entry.name):
                archive.add(entry, arcname=entry.name)


def measure(label, tasks):
    start = time.monotonic()
    command = ['./gradlew', '--no-daemon', *tasks, '--console=plain', '--stacktrace',
               '--max-workers=2', '--profile', '--init-script', str(GUARD)]
    states = {}
    with (REPORTS / f'{label}.log').open('w') as log:
        process = subprocess.Popen(command, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            print(line, end='', flush=True)
            log.write(line)
            match = re.match(r'^> Task (:\S+)(?: (.*))?$', line.rstrip())
            if match:
                states[match[1]] = match[2] or 'EXECUTED'
        code = process.wait()
    result = dict(label=label, sha=git('rev-parse', 'HEAD'), tasks=tasks,
                  seconds=time.monotonic() - start, exit_code=code, states=states)
    (REPORTS / f'{label}.json').write_text(json.dumps(result, indent=2))
    print('CACHE_STUDY_RESULT ' + json.dumps(result), flush=True)
    if code:
        raise subprocess.CalledProcessError(code, command)
    return result


if sys.argv[1] == 'seed':
    shared = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/build-cache-1'
    import shutil
    shutil.copytree(shared, CACHE, dirs_exist_ok=True)
    snapshot('baseline')
    plan = ci_cache.plan_for_paths(paths(PREVIOUS))
    print('AUTOMATIC_PLAN ' + json.dumps(plan), flush=True)
    measure('automatic-warm', plan['gradle-args'].split())
    snapshot('automatic')
    # Separate capability check; its additional cost/outputs are not credited
    # to the source-triggered policy above.
    measure('full-capability-warm', ci_cache.full_plan()['gradle-args'].split())
    snapshot('full')
else:
    mode, workload = sys.argv[2:]
    with tarfile.open(ROOT / f'{mode}.tar') as archive:
        archive.extractall(CACHE, filter='data')
    if workload == 'capability':
        tasks = [task[:-4] + 'testClasses' for task in ci_scope.JVM_TASKS
                 if task != ':app:testDebugUnitTest']
        result = measure('full-cache-hit', tasks)
        hits = [state for task, state in result['states'].items() if task.endswith(':compileTestKotlin')]
        assert len(hits) == 9 and all(state == 'FROM-CACHE' for state in hits), hits
        probe = Path('sources/content/src/test/kotlin/CacheInvalidationProbe721.kt')
        probe.write_text('package cache.study\nclass CacheInvalidationProbe721\n')
        result = measure('changed-test-input', [':source-content:testClasses'])
        assert result['states'][':source-content:compileTestKotlin'] == 'EXECUTED'
        assert list(Path('sources/content/build').rglob('CacheInvalidationProbe721.class'))
    else:
        checks = ci_scope.plan_for_paths(paths(BASE))
        if workload == 'jvm':
            checks = dict(checks, **{'device-24': False, 'device-35': False, 'minified': False})
            tasks = ci_cache.compilation_tasks(checks)
        else:
            assert checks['device-24'] or checks['device-35']
            tasks = [':app:assembleDebug', ':app:assembleDebugAndroidTest']
        print('CONSUMER_PLAN ' + json.dumps(checks), flush=True)
        measure(f'{mode}-{workload}', tasks)
