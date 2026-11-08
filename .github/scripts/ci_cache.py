"""Translate the reviewed CI scope into compilation-only main cache warming."""

import json
import os
from pathlib import Path
import subprocess

import ci_scope


SDK_INPUTS = ('.github/actions/android-sdk/', '.github/ci-environment/sdk-packages/')


def compilation_tasks(checks):
    tasks = []
    for argument in checks['gradle-args'].split():
        if argument == ':app:testDebugUnitTest':
            tasks.append(':app:compileDebugUnitTestKotlin')
        elif argument in ci_scope.JVM_TASKS and argument.endswith(':test'):
            tasks.append(argument[:-len('test')] + 'testClasses')
        elif not argument.startswith('-PciTestScope='):
            raise ValueError(f'No compilation-only mapping for {argument}')
    if checks['device-24'] or checks['device-35']:
        tasks.extend((':app:assembleDebug', ':app:assembleDebugAndroidTest'))
    if checks['minified']:
        # This is the instrumentation APK; :app:assembleBenchmark would run R8.
        tasks.append(':benchmark:assembleBenchmark')
    return list(dict.fromkeys(tasks))


def plan_for_paths(paths):
    return {
        'gradle-args': ' '.join(compilation_tasks(ci_scope.plan_for_paths(paths))),
        'images': any(path.startswith(SDK_INPUTS) or path == '.github/scripts/prepare-android-sdk.sh'
                      for path in paths),
    }


def full_plan():
    return {'gradle-args': ' '.join(compilation_tasks(ci_scope.plan({'full'}))), 'images': True}


def github_plan():
    if os.environ['GITHUB_EVENT_NAME'] in ('workflow_dispatch', 'schedule'):
        return full_plan(), 'Main maintenance run: all compilation and fixed SDK/image caches'
    try:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
        paths = ci_scope.changed_paths(os.environ['GITHUB_EVENT_NAME'], event)
    except (KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        return full_plan(), f'Unavailable push diff; conservatively warm all caches: {error}'
    return plan_for_paths(paths), f'{len(paths)} changed paths compared with the push base'


def main():
    result, reason = github_plan()
    print(json.dumps(dict(result, reason=reason), indent=2))
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        for key, value in result.items():
            output.write(f'{key}={str(value).lower() if isinstance(value, bool) else value}\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
        summary.write(f'### Main cache compilation\n\n{reason}\n\n')
        summary.write(f"Compile tasks: `{result['gradle-args'] or 'none'}`\n\n")
        summary.write(f"Separate SDK/image warmup: `{result['images']}`\n")


if __name__ == '__main__':
    main()
