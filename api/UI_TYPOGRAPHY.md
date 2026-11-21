# UI typography

Use these roles with `MaterialTheme.typography`. The names come from the existing
theme API; their actual values, rather than Material's default size hierarchy,
define the app's hierarchy. Sizes and line heights below are in **sp** and follow
the system font scale.

| Role | Style | Size / line height | Weight | Representative UI |
| --- | --- | --- | --- | --- |
| Full-screen page title | `displayLarge` | 22 / 28 | 600 | Settings, sources, discovery results, speech settings |
| Settings section | `bodyLarge` through `SectionHeader` | 15 / 24 | 600 (explicit override) | Extensions, reading, display and data groups |
| Ordinary bottom-sheet title | `displayMedium` | 19 / 26 | 600 | Reader settings, chapter directory, source range/pages, search range/failures, export |
| Settings row main text | `headlineSmall` | 17 / 25.5 | 400 | `SettingsClickableEntry`, `SettingsSwitchEntry`, menu/slider entries |
| Row supporting text | `bodyMedium` | 14 / 20 | 400 | Settings descriptions, source/book descriptions |
| Compact content/section title | `titleMedium` | 16 / 24 | 500 | Discovery sections and book cards |
| Compact secondary title | `titleSmall` | 14 / 20 | 500 | Search history, compact book labels |

`titleLarge` retains Material's 22 / 28, weight 400. It is still used for special
content such as statistics, dialog content and font/paper previews; it is not the
default choice for a new page or ordinary sheet title. The read-aloud player's
book title keeps its existing 22 / 28, weight 500, two-line presentation. The
search hub uses an input field as its toolbar, and the discovery source scope
uses a compact selector. Neither should be replaced with a page heading.

## Layout and overflow

- Top app bar titles use one line and `TextOverflow.Ellipsis`. Keep the complete
  string in `Text` so accessibility services can read it. Reserve space for back
  and action buttons; do not shrink the font to fit a long title.
- Section and sheet headings wrap naturally, without a fixed text height.
  Discovery section headers retain their adaptive action placement.
- Settings entries grow with wrapped main/supporting text. Dense book lists may
  keep their existing line limits and ellipsis.
- `displayLarge` now has a 28sp line height instead of 18sp. All its users inherit
  that correction, including reader chrome, book-detail headings and storage
  totals. Its size and weight are unchanged. Do not compensate with a fixed 18dp
  container or by reducing line height below font size.

## Compatibility and review

The public `AppTypography` property and all existing style names remain available.
Only `displayLarge`'s line height changes globally; other roles are selected at
their actual use sites. Colors, corner radii, card spacing and reader paper
palettes remain as defined by the existing components.

Reader **body text** is outside this UI convention. `ReaderLayoutSettings.textStyle`
continues to derive font size, weight and line height from reader preferences;
pagination and scrolling share that style. Never migrate reader content by
replacing `display`/`headline`/`body` names globally.

For changes to these roles, inspect representative pages and sheets in Chinese
and English, light and dark themes, with short and wrapping/ellipsized titles.
Check normal, 1.3×, 1.5× and 2× font scales on a narrow phone. Confirm that glyphs
and line spacing remain readable, titles do not collide with actions, and rows
can grow. Review screenshots for native text layout and toolbar bounds.
