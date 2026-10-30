"""Verify the exact, patched source inventory without downloading anything."""

import hashlib
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def verify_sources():
    expected = {}
    for line in (ROOT / 'sources.sha256').read_text(encoding='utf-8').splitlines():
        digest, name = line.split('  ', 1)
        path = ROOT / name
        if not path.resolve().is_relative_to((ROOT / 'vendor').resolve()):
            raise ValueError(f'Source inventory path outside vendor: {name}')
        if name in expected:
            raise ValueError(f'Duplicate source inventory path: {name}')
        expected[name] = digest
    actual = {p.relative_to(ROOT).as_posix() for p in (ROOT / 'vendor').rglob('*') if p.is_file()}
    if actual != expected.keys():
        raise ValueError(f'Source inventory mismatch: {sorted(actual ^ expected.keys())}')
    for name, digest in expected.items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f'Source hash mismatch: {name}')
    return len(expected)


if __name__ == '__main__':
    print(f'Verified {verify_sources()} vendored source files.')
