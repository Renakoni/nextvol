"""Bound variant task payloads before uploading a new cache snapshot."""

import os
from pathlib import Path
import re


MAX_BYTES = 512 * 1024 * 1024


def trim_cache(directory, limit=MAX_BYTES):
    # Gradle's content-addressed entries are compressed files named by a 128-bit
    # hash. Leave its locks/metadata alone; only discard reusable task payloads.
    entries = [(path, path.stat()) for path in directory.iterdir()
               if re.fullmatch(r'[0-9a-f]{32}', path.name) and path.is_file()]
    retained = 0
    for path, stat in sorted(entries, key=lambda entry: (entry[1].st_mtime_ns, entry[0].name), reverse=True):
        # Prefer newly produced outputs. A single oversized entry must not
        # prevent smaller useful entries from fitting in the snapshot.
        if retained + stat.st_size <= limit:
            retained += stat.st_size
        else:
            path.unlink()
    return sum(stat.st_size for _, stat in entries), retained


if __name__ == '__main__':
    before, after = trim_cache(Path(os.environ['CI_GRADLE_BUILD_CACHE_DIR']))
    print(f'Variant task payloads: {before} -> {after} bytes (limit {MAX_BYTES})')
