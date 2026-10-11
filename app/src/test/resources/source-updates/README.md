# Pixiv update fixtures

`pixiv-v284.json` is the main novel entry from the upstream PixivSource release
284 (source date 2026-09-21), retrieved on 2026-09-29 from
`https://raw.githubusercontent.com/DowneyRem/PixivSource/main/pixiv.json`.
The CDN response matched the GitHub response during this verification. The full
collection hash was `c89c54142352d6298fa49cfccb35c8a6428381fdfd1825d5757a9810257a634f`.
The selected entry, recursively sorted by object key and serialized as compact
UTF-8 JSON, hashes to `7e2acfc99886fa084433dc67a3f7f050cedae94a7135ba8564664e3708a2faf9`.

`pixiv-adapted-previous.json` is the previously shipped adaptation from commit
`0cd73347`. It exercises real update boundaries without fabricating
content digests. Its canonical digest is
`04651074a09090be288ece1fae4e67081143696e00473d98a19def8ad7884a6e`.

These fixtures contain legacy features intentionally. They are test resources,
not release assets, and must never be installed directly by the update path.
Tests perform no live upstream requests.

The first adapter supports this reviewed upstream snapshot only. It maps the
whole definition to the maintained adaptation in the source catalog; it does
not combine a new library with an old interface, and does not infer compatibility
from the displayed version or timestamp. Unknown changes are rejected. Adding
a release requires reviewing its full definition, updating the complete local
adaptation and snapshot, and rerunning update, account, group and reading tests.
