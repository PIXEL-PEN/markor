# Autumn Changelog

Autumn is a journal and personal archive for Android, forked from
[Markor](https://github.com/gsantner/markor) and relicensed under GPL-3.0.
See LICENSE.txt for the attribution that comes with that.

Nothing in the app reads this file. It is a record for the repository.

## Unreleased — the rebuild

92 commits since `markor-plus-v1.2.6`. Markor is a general-purpose text editor;
Autumn is a journal that happens to store plain Markdown. Most of the work has
been deciding what a journal does not need.

### Identity
- Renamed to Autumn; package is `net.pixelpen.journal`.
- Relicensed GPL-3.0, copyright PIXEL-PEN. Markor's Apache 2.0 notice and the
  writeily-pro MIT ancestry are retained in LICENSE.txt.
- All four product flavours removed; debug and release only. Debug installs
  alongside release as `.dev` and writes to its own archive folder.

### Strictly offline
- `INTERNET` and `REQUEST_INSTALL_PACKAGES` permissions removed from the
  manifest. The app cannot reach the network.
- Every outbound link dropped from the About screen: app page, bug reports,
  help, rate, translate, community, source.
- Build pinned to English with `resConfigs`. The 63 inherited locale folders
  were incomplete and carried none of Autumn's own strings.
- All renderer assets confirmed local, under `file:///android_asset`: KaTeX,
  AsciiDoc, Orgmode, Mermaid, Prism.

### Capture
- Calendar is the first tab, so the app opens on capture rather than a file
  browser. Date, category and title, with the stamp written as line 1.
- Capture moved to the FAB; Reset and Submit buttons dropped.
- A second, smaller FAB saves a title-only note without opening the editor.
  Titles run to 252 characters, so many entries need no body at all.
- Android's `CalendarView` replaced with a custom `MonthGrid`. The stock widget
  ignores colour and text attributes on API 21+ and caused the month-browsing
  quirk noted under 1.2.6.
- One home folder, `/Documents/Autumn`, decided once. QuickNote and ToDo tabs
  gone.

### Tree
- Drawn natively instead of in a WebView.
- Rename, move and delete on long-press. Folder delete.
- Journal years separated from everything else; an "Other files" section that
  starts closed.
- Categories collapse on leaving the screen and expand only on a note entry or
  a category change, rather than unfolding themselves.

### Year and timeline
- Year overview as a native screen, reaching back through past years.
- Highlights are open-ended and ordered by the month they are about, not when
  they were written.
- A toggled personal-growth section.
- Months are fixed furniture rather than text inside the note.
- HTML timeline export: self-contained, images inlined as data URIs, decade
  bands, a lightbox and a print stylesheet.

### Images and attachments
- One central store: `Images/<year>` and `Files/<year>` under the home folder.
  The two insert paths that used to disagree now agree.
- Thumbnail loader with an LRU cache and a single decode thread.
- Full-screen image viewer, swipeable.
- Archive backup to a zip, anywhere.

### Search
- Search the archive from the toolbar, landing on the matched line.
- Results get a screen of their own and show the category, not the full path.

### Settings
- 90 preferences across 16 screens, down to 25 across four.
- One destination: the gear tab and the overflow menu now show the same tree.
  The tab used to host a short screen whose first row was a shortcut to the
  real settings.
- Basic colour scheme removed; the editor takes its colours from the theme.
- View mode is a rule rather than a setting: browsing opens in view mode,
  writing entry points open the editor, the top bar toggle switches.
- Single newline is always a line break. Standard Markdown folds it into the
  paragraph above, which turns a phone-typed entry into a wall of text.
- Five of six format screens removed. The formats still work from defaults.
- The changelog and licence dialog no longer ambush launch. It was gated on
  `versionCode`, which is `yyMMdd`, so any build made on a later day looked
  like an upgrade.

### Known gaps
- Dark and Black themes: Autumn's eight own colours have no night variant, so
  the Year and Calendar screens stay light while the rest goes dark. The Tree
  inherits the theme and behaves. Listed in `values-night/colors.xml`.
- `FormatRegistry` still declares nine formats and the editor's overflow menu
  will switch a note to any of them, CSV and embed-binary included.
- Source files added by this fork carry no licence header.
- Still to come: year-overview and whole-archive HTML exports, an in-app guide.

---

## Markor Plus era

Kept as a record. This predates the Autumn rename and most of the above; its
limitations are listed as they stood then and several are now resolved.

### [1.2.6] - 2025-08-30
#### Added
- Doc icon badges for long-title notes (>120 chars): hollow dot = title-only,
  filled red dot = has body content.
- README and module changelog documenting Calendar Input + Folder Tree.

#### Changed
- Date-stamp emphasised at the top of new notes (non-US style).

#### Fixed
- Creation-time chronology preserved; editing a note does not bump its position.

#### Limitations as of 1.2.6
- Rename, move and copy went through Markor's native browser — since replaced
  by long-press actions in the Tree.
- `CalendarView` quirk when browsing months — since removed with `MonthGrid`.
- Intermittent wrong datestamp on new notes, worked around with Reset — Reset
  no longer exists; capture now owns the stamp.
- Very large archives may still affect scanning; `.nomedia` or `.skip-scan`
  can mitigate.
- Root paths vary across devices; no automated migration.
