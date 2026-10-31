"""Bound CI cache snapshots using trusted main workflow code only."""

import argparse
from collections import defaultdict
import json
import os
from pathlib import Path
import re
import subprocess


MAIN = 'refs/heads/main'
GRADLE_HOME = 'gradle-home-v1|Linux-X64|'
GRADLE_PARTS = tuple(f'gradle-{kind}-v1-' for kind in (
    'generated-gradle-jars', 'wrapper-zips', 'java-toolchains', 'dependencies',
    'instrumented-jars', 'kotlin-dsl', 'groovy-dsl', 'transforms',
))


def fixed_tool(key):
    if key.startswith('android-linux-sdk'):
        return 'sdk'
    match = re.fullmatch(r'android-linux-emulator[^/]+-api(24|35)-[^/]+', key)
    if match:
        return f'api-{match[1]}'
    if key.startswith('robolectric-linux-'):
        return 'robolectric'
    return None


def obsolete_snapshots(caches):
    groups = defaultdict(list)
    for cache in caches:
        ref, key = cache['ref'], cache['key']
        tool = fixed_tool(key)
        if ref == MAIN and tool:
            groups[(ref, tool)].append(cache)
        variant = re.fullmatch(r'gradle-tasks-linux-(release|benchmark)-v1-[0-9a-f]{40}', key)
        if variant and (ref == MAIN or re.fullmatch(r'refs/pull/\d+/merge', ref)):
            groups[(ref, variant[1])].append(cache)
        if key.startswith('onnxruntime-linux-v1-') and (ref == MAIN or re.fullmatch(r'refs/pull/\d+/merge', ref)):
            # PRs may change ORT itself; main's different runtime must not evict theirs.
            groups[(ref, 'onnxruntime')].append(cache)

    obsolete = []
    for entries in groups.values():
        obsolete.extend(sorted(entries, key=lambda item: item['created_at'], reverse=True)[1:])
    shared_tools = {fixed_tool(cache['key']) for cache in caches if cache['ref'] == MAIN}
    for cache in caches:
        tool = fixed_tool(cache['key'])
        if tool and tool in shared_tools and re.fullmatch(r'refs/pull/\d+/merge', cache['ref']):
            obsolete.append(cache)
    return obsolete


def obsolete_gradle(caches, key):
    root = next(cache for cache in caches if cache['ref'] == MAIN and cache['key'] == key)
    metadata = Path.home() / '.gradle/.setup-gradle/gradle-home-entry-metadata.json'
    entries = json.loads(metadata.read_text(encoding='utf-8'))['entries']
    referenced = {entry['cacheKey'] for entry in entries}
    available = {cache['key'] for cache in caches if cache['ref'] == MAIN}
    if not referenced or not referenced <= available:
        raise RuntimeError('Gradle snapshot is incomplete; keep its existing cache graph for recovery')
    # The archive was restored by its exact key. Keep all of its extracted
    # payloads, rather than guessing that the newest payload of each kind fits.
    return [cache for cache in caches
            if cache['ref'] == MAIN and cache['created_at'] <= root['created_at']
            and cache['key'] != key and cache['key'] not in referenced
            and (cache['key'].startswith(GRADLE_HOME) or cache['key'].startswith(GRADLE_PARTS))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--select-gradle', help='Select the warmer snapshot for this run ID and attempt')
    parser.add_argument('--gradle-key', help='Prune old Gradle entries after restoring this exact archive')
    args = parser.parse_args()
    endpoint = f"repos/{os.environ['GH_REPO']}/actions/caches"
    pages = json.loads(subprocess.check_output(
        ['gh', 'api', '--paginate', '--slurp', f'{endpoint}?per_page=100'], encoding='utf-8'))
    caches = [cache for page in pages for cache in page['actions_caches']]
    if args.select_gradle:
        matches = [cache['key'] for cache in caches if cache['ref'] == MAIN
                   and cache['key'].startswith(GRADLE_HOME)
                   and cache['key'].endswith(f'-{args.select_gradle}')]
        if len(matches) != 1:
            raise RuntimeError('Expected one newly saved main Gradle snapshot; refusing to prune')
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write(f'key={matches[0]}\n')
            output.write(f"home={Path.home() / '.gradle'}\n")
        return

    obsolete = obsolete_snapshots(caches)
    if args.gradle_key:
        obsolete.extend(obsolete_gradle(caches, args.gradle_key))
    for cache in obsolete:
        print(f"{'Would delete' if args.dry_run else 'Deleting'} {cache['id']} "
              f"{cache['ref']} {cache['key']} ({cache['size_in_bytes']} bytes)", flush=True)
        if not args.dry_run:
            result = subprocess.run(['gh', 'api', '--method', 'DELETE', f"{endpoint}/{cache['id']}"],
                                    capture_output=True, text=True, encoding='utf-8')
            # Another cleanup or GitHub's quota eviction may have removed it.
            if result.returncode and '(HTTP 404)' not in result.stderr:
                raise RuntimeError(result.stderr)
    print(f"Selected {len(obsolete)} caches, {sum(cache['size_in_bytes'] for cache in obsolete)} bytes")


if __name__ == '__main__':
    main()
