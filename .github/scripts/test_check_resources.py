from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from check_resources import LANGUAGES, check, parameters


class ResourceContractTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for language in LANGUAGES:
            self.write(language, '<string name="message">%1$d / %2$s</string>')

    def write(self, language, contents, filename='strings.xml'):
        directory = self.root / language
        directory.mkdir(exist_ok=True)
        (directory / filename).write_text('<resources>' + contents + '</resources>', encoding='utf-8')

    def test_reordering_repetition_and_literal_percent_are_valid(self):
        self.write('values-zh-rTW', '<string name="message">%2$s: %1$d%% (%1$d)</string>')
        self.assertEqual([], check(self.root))
        self.assertEqual({1: 'float'}, parameters('%1$.1f×'))
        self.assertEqual({1: 'integer'}, parameters('%d %<d'))

    def test_new_key_fails_until_every_supported_language_has_it(self):
        self.write('values', '<string name="new_action">New action</string>', 'new.xml')
        failures = check(self.root)
        self.assertEqual(2, len(failures))
        self.assertTrue(all('missing new_action' in failure for failure in failures))

    def test_missing_argument_and_invalid_conversion_fail(self):
        for template in ['%1$d', '%1$q / %2$s', '%1$d / %2$', '%0$d / %2$s', '%-s', '%+s',
                         '%1$.2d', '%+%', '%-05d', '%+ d', '%#g', '%,e', '%(a']:
            with self.subTest(template=template):
                self.write('values-zh-rTW', f'<string name="message">{template}</string>')
                self.assertTrue(check(self.root))

    def test_literal_percent_requires_explicit_nonformatted_contract(self):
        for language in LANGUAGES:
            self.write(language, '<string name="message" formatted="false">50% of viewport</string>')
        self.assertEqual([], check(self.root))
        self.write('values-zh-rTW', '<string name="message">50% of viewport</string>')
        self.assertTrue(any('formatted attribute' in failure for failure in check(self.root)))

    def test_duplicate_resource_in_another_file_fails(self):
        self.write('values-zh-rTW', '<string name="message">%1$d / %2$s</string>', 'duplicate.xml')
        self.assertTrue(any('duplicate resource' in failure for failure in check(self.root)))

    def test_plural_categories_are_language_specific_and_each_branch_is_checked(self):
        for language, quantities in LANGUAGES.items():
            self.write(language, '<plurals name="count">' + ''.join(
                f'<item quantity="{quantity}">%1$d</item>' for quantity in quantities) + '</plurals>')
        self.assertEqual([], check(self.root))
        self.write('values', '<plurals name="count"><item quantity="other">%1$d</item></plurals>')
        self.assertTrue(any('missing quantities' in failure for failure in check(self.root)))
        self.write('values', '<plurals name="count"><item quantity="one">%1$d</item>'
                   '<item quantity="other">%1$d</item></plurals>')
        self.write('values-zh-rTW', '<plurals name="count"><item quantity="other">%1$s</item></plurals>')
        self.assertTrue(any('other: arguments' in failure for failure in check(self.root)))

    def test_array_item_arguments_and_count_are_checked(self):
        for language in LANGUAGES:
            self.write(language, '<string-array name="array"><item>%d</item><item>%s</item></string-array>')
        self.assertEqual([], check(self.root))
        for contents in ['<item>%s</item><item>%d</item>', '<item>%d</item>']:
            self.write('values-zh-rTW', '<string-array name="array">' + contents + '</string-array>')
            self.assertTrue(check(self.root))

    def test_nontranslatable_names_do_not_require_translations(self):
        self.write('values', '<string name="product" translatable="false">Example</string>', 'names.xml')
        self.assertEqual([], check(self.root))

    def test_command_fails_for_invalid_resource_then_succeeds_after_restoring(self):
        script = Path(__file__).with_name('check_resources.py')
        self.write('values-zh-rTW', '<string name="message">%1$s / %2$d</string>')
        failed = subprocess.run([sys.executable, str(script), str(self.root)], capture_output=True)
        self.assertNotEqual(0, failed.returncode)
        self.write('values-zh-rTW', '<string name="message">%1$d / %2$s</string>')
        restored = subprocess.run([sys.executable, str(script), str(self.root)], capture_output=True)
        self.assertEqual(0, restored.returncode, restored.stdout)


if __name__ == '__main__':
    unittest.main()
