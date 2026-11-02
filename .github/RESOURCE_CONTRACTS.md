# Translation resource contracts

Run without Gradle:

```sh
python3 .github/scripts/test_check_resources.py
python3 .github/scripts/check_resources.py
```

The checker compares translatable `string`, `plurals`, and `string-array`
resources in `app/src/main/res/values` with Simplified Chinese (`values-zh-rCN`)
and Traditional Chinese (`values-zh-rTW`). Add new keys to all supported
languages.

Checks include missing/defaultless/duplicate keys, resource types, `formatted`
attributes, argument positions and type families, common invalid format syntax,
array item contracts, and each plural branch. Argument order and repeated uses
may differ. English needs `one/other` and Chinese `other`.
Literal percent signs in text that is never formatted use
`formatted="false"` consistently across translations.

This is a resource contract check, not a full Java Formatter implementation,
call-site type analysis, or language-quality assessment. Android Lint and actual
resource/Compose tests remain necessary. Dynamic website content is outside
this XML check. Other modules and qualified resource
overrides beyond the listed directories are outside its coverage.

Identical text is reviewed, not automatically treated as missing translation.
The existing exceptions are brand/font names (`Bangumi`, `Source Han Serif`,
`LXGW WenKai`), translator credits, the font sample `永 Aa`, the `r18`/`CI` labels, original source names
in `source_examples_*`, technical `ID`/`SHA-256`/HTTP labels, and templates that
contain only parameters, punctuation, units, or percentages (`source_page_*`,
`sources_import_problem`, `sources_check_summary`, `tts_factor`,
`update_notification_progress`). These are explicit resources, not English
fallback. Do not copy English interface sentences to satisfy coverage.

`DirectorySearchResourcesTest` checks search prompts and chapter counts in all
supported languages. Bookmark resource tests also check integer/string formatting.
Compose tests cover representative long translated
source controls at 320dp with 1.6× font size; this does not assert that every
screen or device configuration has been visually tested.
