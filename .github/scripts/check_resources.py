"""Check the app's supported translations and Android string format contracts."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


LANGUAGES = {'values': {'one', 'other'}, 'values-zh-rCN': {'other'},
             'values-zh-rTW': {'other'}}
RESOURCE_TYPES = {'string', 'plurals', 'string-array'}
FORMAT = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.(\d+))?([tT])?([a-zA-Z%])')


def parameters(text):
    """Return argument positions/types; translation order and repeated uses may differ."""
    arguments = {}
    position = 0
    previous = None
    offset = 0
    while (offset := text.find('%', offset)) >= 0:
        token = FORMAT.match(text, offset)
        if token is None:
            raise ValueError(f'invalid format near {text[offset:offset + 16]!r}')
        index, flags, width, precision, date, conversion = token.groups()
        offset = token.end()
        if conversion not in 'bBhHsScCdoxXeEfgGaA%n' and not date:
            raise ValueError(f'invalid conversion {token[0]}')
        if (len(set(flags)) != len(flags) or ('-' in flags or '0' in flags) and width is None
                or {'-', '0'} <= set(flags) or {'+', ' '} <= set(flags)):
            raise ValueError(f'invalid flags {token[0]}')
        if conversion in '%n' and not date:
            if index or precision is not None or set(flags) - {'-'} or conversion == 'n' and (flags or width):
                raise ValueError(f'invalid literal format {token[0]}')
            continue
        if date:
            if conversion not in 'HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc':
                raise ValueError(f'invalid date conversion {token[0]}')
            kind = 'date'
        elif conversion in 'doxX':
            kind = 'integer'
        elif conversion in 'eEfgGaA':
            kind = 'float'
        elif conversion in 'cC':
            kind = 'character'
        else:
            kind = 'string'
        allowed_flags = '-<' if kind in {'string', 'character', 'date'} else '-+ 0,(<'
        if conversion in 'oxX':
            allowed_flags = '-#0<'
        elif conversion in 'eE':
            allowed_flags = '-#+ 0(<'
        elif conversion == 'f':
            allowed_flags = '-#+ 0,(<'
        elif conversion in 'aA':
            allowed_flags = '-#+ 0<'
        if set(flags) - set(allowed_flags) or precision is not None and kind in {'integer', 'character', 'date'}:
            raise ValueError(f'invalid flags/precision {token[0]}')
        if '<' in flags:
            if index or previous is None:
                raise ValueError(f'invalid previous argument {token[0]}')
            index = previous
        elif index is not None:
            index = int(index)
        else:
            position += 1
            index = position
        if index < 1 or index in arguments and arguments[index] != kind:
            raise ValueError(f'conflicting argument {token[0]}')
        arguments[index] = kind
        previous = index
    return arguments


def read_resources(directory, errors):
    resources = {}
    for path in sorted(directory.glob('*.xml')):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as failure:
            errors.append(f'{path}: {failure}')
            continue
        for node in root:
            if node.tag not in RESOURCE_TYPES:
                continue
            key = node.get('name')
            if key in resources:
                errors.append(f'{path}: duplicate resource {key}')
            resources[key] = (node, path)
    return resources


def contract(node):
    def item_contract(item):
        return {} if node.get('formatted') == 'false' else parameters(''.join(item.itertext()))

    if node.tag == 'string':
        return item_contract(node)
    if node.tag == 'string-array':
        return [item_contract(item) for item in node]
    quantities = {item.get('quantity'): item_contract(item) for item in node}
    if len(quantities) != len(node):
        raise ValueError('duplicate plural quantity')
    if any(q not in {'zero', 'one', 'two', 'few', 'many', 'other'} for q in quantities):
        raise ValueError('invalid plural quantity')
    if 'other' not in quantities:
        raise ValueError('missing plural other')
    return quantities


def check(res):
    errors = []
    default = read_resources(res / 'values', errors)
    if not default:
        return [f'{res}: no default string resources found']
    for directory, required_quantities in LANGUAGES.items():
        translated = default if directory == 'values' else read_resources(res / directory, errors)
        for key, (reference, _) in default.items():
            if reference.get('translatable') == 'false':
                continue
            if key not in translated:
                errors.append(f'{directory}: missing {key}')
                continue
            node, path = translated[key]
            try:
                if node.tag != reference.tag:
                    raise ValueError(f'resource type {node.tag} differs from {reference.tag}')
                if node.get('formatted', 'true') != reference.get('formatted', 'true'):
                    raise ValueError('formatted attribute differs from default')
                expected, actual = contract(reference), contract(node)
                if node.tag == 'plurals':
                    missing = required_quantities - actual.keys()
                    if missing:
                        raise ValueError(f'missing quantities {sorted(missing)}')
                    for quantity, args in actual.items():
                        if args != expected.get(quantity, expected['other']):
                            raise ValueError(f'{quantity}: arguments {args} differ from default')
                elif actual != expected:
                    raise ValueError(f'arguments {actual} differ from default {expected}')
            except ValueError as failure:
                errors.append(f'{path}: {key}: {failure}')
        for key in translated.keys() - default.keys():
            errors.append(f'{directory}: {key} has no default resource')
    return errors


if __name__ == '__main__':
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2] / 'app/src/main/res'
    failures = check(root)
    for failure in failures:
        print(failure)
    print(f'Resource contracts: {len(failures)} error(s)')
    sys.exit(bool(failures))
