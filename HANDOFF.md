# Autumn — Handoff

Written at commit `b64569d7`. Read this before changing anything; several
decisions below look arbitrary until you know what they cost to learn.

---

## 1. What this is

Autumn is a journal and personal archive for Android, forked from
[Markor](https://github.com/gsantner/markor) at tag `markor-plus-v1.2.6`.
Repo: `github.com/PIXEL-PEN/markor`. Package `net.pixelpen.journal`.
Licence GPL-3.0, copyright PIXEL-PEN (placeholder).

Markor is a general-purpose text editor. Autumn is a journal that happens to
store plain Markdown. **Most of the work has been deciding what a journal does
not need**, and that framing resolves most design questions: if a feature
serves a text editor rather than someone keeping a journal, it goes.

Notes live in `/Documents/Autumn`, organised as categories containing
`<Category>/<Year>/` note files, with `Images/<year>` and `Files/<year>` for
attachments. The archive is plain files on disk, readable without the app —
that is the point of it.

### Hard constraints

- **Strictly offline.** `INTERNET` and `REQUEST_INSTALL_PACKAGES` are removed
  from the manifest. The app *cannot* reach the network. Do not add a feature
  that needs it, and do not add a link that leaves the app.
- **Plain files, no database.** The filesystem is the data model.
- **English only.** `resConfigs "en"` in `app/build.gradle`.

---

## 2. Build

```
./gradlew installDebug      # installs as net.pixelpen.journal.dev
```

AGP 8.8.0, Gradle 8.10.2, compileSdk 35, minSdk 24, JDK 21.

**There are no product flavours.** Four were removed; `debug` and `release` are
the only variants, so the task is `installDebug`, not `installDevDebug`. Debug
carries `applicationIdSuffix ".dev"` and writes to `Autumn (dev)`, so it sits
alongside a release install and cannot touch the live archive.

`app/thirdparty/java` is a **second source root** and `app/thirdparty/assets` a
second asset root, both added by `app/build.gradle` `sourceSets`. Any tool that
walks the source tree must include them — one of my checks missed a bug for
exactly this reason.

### Landmine: versionCode is a date

```groovy
versionCode Integer.parseInt(new Date().format('yyMMdd'))
```

It changes **every day**. Anything gated on "is this a new version" fires on any
build made on a later day than the last install. This is what made the changelog
dialog ambush launch, and it will bite the next thing gated that way.

### Landmine: res/raw is partly generated

`build.gradle`'s `copyRepoFiles` task copies root files into
`app/src/main/res/raw/`, lowercasing them, before every build. That is why
`R.raw.readme` resolves with no such file committed. The generated copies are
gitignored. Currently only `README.md` and `LICENSE.txt` are copied.

If you go looking for `res/raw/readme.md` and don't find it, this is why. I lost
time believing I'd written code that couldn't compile.

---

## 3. Verification — read this before writing code

**There is no compiler in the Claude session.** Everything below exists because
a broken commit costs the user a build cycle, and one shipped AAPT error
(a duplicate `<string>`) cost them an evening.

```
bash /home/claude/check-vs-base.sh     # the one to run
```

Three layers:

| Script | What it does |
|---|---|
| `check.py` | Static checks on the tree as it stands: brace/structure balance, field and type sanity, unresolved `R.string` / `R.color` / `R.drawable` / `R.layout` / `R.id` / `R.raw`, XML well-formedness, `--` inside XML comments, duplicate resource names, undeclared locals, imports of deleted classes |
| `check-vs-base.sh` | Runs `check.py` at the base tag and now, and reports only the **difference**. Compares by basename, because the `net.gsantner.markor` → `net.pixelpen.journal` rename makes every finding in a renamed file read as new |
| `check-removed-methods.py` | Compares method declarations at the base tag against now for watched classes, and fails if a removed method is still called — including from inside its own class |

### The gate

```sh
if bash check-vs-base.sh 2>&1 | grep -q "NO NEW PROBLEMS"; then ... ; fi
```

**Do not gate on `check.py`'s exit code.** It is always non-zero: there are 48
pre-existing findings at the base tag. I made that mistake and it silently
skipped a commit.

Current baseline: **48 at base, 46 at HEAD.** "NO NEW PROBLEMS" is the only
acceptable state before sending.

### What the checks cannot do

They do not compile, and they do not reason. They catch typos, imbalance and
dangling references. They do **not** catch logic errors, Android lifecycle
mistakes, or layout behaviour. For those, three habits earned their place:

1. **Scripted edits that assert on their anchor.** Every edit asserts the exact
   old text occurs exactly once before replacing. A regex that "walks back to
   the preceding `public`" ate a method whose signature spanned two lines.
2. **Round-trip simulation in Python** for anything stateful before shipping —
   a spinner's event sequence, a parse/serialise cycle.
3. **Validate inside the cloned bundle**, not the working tree. Clone the
   bundle, then parse every XML in the clone.

### Never invent a resource name

I invented `ic_lock_black_24dp`, `needs_restart_to_apply_the_setting` and
`R.string.copied_to_clipboard` across this session. All three were caught, but
the cheaper habit is: **when moving markup, recover the original verbatim from
git** rather than retyping it.

```sh
git show <rev>:path/to/file.xml
```

---

## 4. Delivery

The user builds locally from a git bundle.

```sh
git bundle create autumn-<hash>-incr.bundle <their-last-known-hash>..HEAD
```

**Send incremental bundles.** ~20 KB versus 42 MB for `--all`. I sent full
bundles far too long out of defensiveness; two "does not look like a v2 or v3
bundle file" failures were simply the user running `verify` before 42 MB had
finished downloading. Incremental bundles make that impossible.

Pick a prerequisite you are confident they have; an ancestor is safe either way,
and git fails loudly rather than misapplying.

Always test-clone before sending:

```sh
git clone -q <bundle> /tmp/x   # full bundle
# or, for incremental: clone the repo, checkout the prerequisite, pull the bundle
```

Name every bundle by its commit hash. Re-sending the same filename means the
browser saves `(1)` and the user keeps building the stale one. This happened
twice.

Better still: push a branch to `PIXEL-PEN/markor` and skip file transfer.

---

## 5. Architecture, and why

### Navigation
Four tabs, order fixed by `main__bottom_nav.xml`: **Calendar, Tree, Year,
Settings**. `MainActivity.tabIdToPos` / `getPosTitle` / `getPosFragment` and
`SectionsPagerAdapter.getItemCount()` all follow that menu. **Change one, change
all.**

Calendar is first so the app opens on capture, not on a file browser.

### Capture
`CalendarFragment` owns what capturing means: picked date, category, title, and
the date stamp written as line 1. The FAB only asks it to proceed. A second,
smaller FAB saves a title-only note without opening the editor — titles run to
252 characters, so many entries need no body.

`MonthGrid` replaces Android's `CalendarView`, which **ignores
`dateTextAppearance` and colour attributes on API 21+** in Material mode; only
`colorAccent` works. That is why there is a custom grid.
`MonthGrid.setMarkedDays()` is built and **not wired** — marking days that have
notes is an open item.

### Edit vs view mode
Decided by **how the document was reached**, not by a setting:

- browsing → view mode (Tree, Home browser, a link inside a note)
- writing → editor (new note from Calendar, year growth note, jump to a line)
- the top bar toggle switches; nothing is remembered per document

Callers pass `Boolean.FALSE` to `DocumentActivity.launch` for the editor,
`Boolean.TRUE` for view, `null` to take the default.

**`DocumentActivity` must read that flag with `hasExtra`, not
`getBooleanExtra(.., false)`** — otherwise an explicit `false` is
indistinguishable from no flag and the caller's instruction is silently dropped.
That bug was latent and harmless only while the default happened to agree.

### Settings
**25 preferences across four screens, from 90 across sixteen.** One destination:
the gear tab and the overflow menu both show `SettingsFragmentMaster`.

Because that fragment is now hosted twice, two things are host-aware and must
stay that way:

- a theme change calls `finish()` from `SettingsActivity` but `recreate()` from
  the tab — `finish()` from the tab closes the app
- `MoreFragment.onBackPressed()` walks back up preference sub-screens, because
  in the tab the press arrives at `MainActivity` first. Without it, Back from
  "Edit mode" leaves the app

Removing a preference does **not** remove behaviour: the getter stays and answers
from its default. That is how five format screens disappeared while every format
kept working. Check callers before deleting a getter.

### Markdown
Single newline is **always** a hard line break
(`isMarkdownNewlineNewparagraphEnabled()` returns `true`). Standard Markdown
folds a single newline into the paragraph above, which turns a phone-typed
journal entry into a wall of text — and view mode is what browsing opens in.

### Rendering
All renderer assets are local under `file:///android_asset`, in
`app/thirdparty/assets`: KaTeX, AsciiDoc, Orgmode, Mermaid, Prism (~7.3 MB).
Nothing fetches. Verify this stays true.

---

## 6. Open items

### Known gaps, in rough priority order

1. **Dark / Black theme.** Autumn's eight own colours have no night variant, so
   Year and Calendar stay light while the rest goes dark; Black makes a pit of
   the Tree. The list is in `values-night/colors.xml`. Autumn's layouts contain
   **zero hardcoded hex** and `values-night` already works, so this is seven
   night colours plus one stray `@android:color/white` in
   `fragment_calendar.xml` — an hour with the device, not a reconfiguration.
   The Tree is the screen that already behaves, because it inherits the theme
   background instead of setting its own.
2. **Licensing final pass.** Append the canonical GPL-3 text to `LICENSE.txt`
   (`curl https://www.gnu.org/licenses/gpl-3.0.txt`), replace the `PIXEL-PEN`
   placeholder, then delete the "not in this file yet" paragraph. Source files
   added by this fork carry **no licence header**; inherited files keep their
   Apache ones. Apache 2.0 is one-way compatible with GPL-3, which is what makes
   the relicence possible — not legal advice.
3. **`FormatRegistry` declares nine formats** and the editor's overflow menu will
   switch a note to any of them, CSV and embed-binary included. A footgun on a
   journal note. The unused ones carry ~3.5 MB of assets.
4. **Exports.** Year-overview HTML and a whole-archive HTML site. The archive
   site should share one `Images/` folder rather than inlining, unlike the
   Timeline export which is deliberately self-contained.
5. **In-app guide.**
6. **Field testing**, then final UI pass.

### Parked bugs

- Search scroll-to-line lands imprecisely in some cases.
- `MonthGrid.setMarkedDays()` unwired — days with notes are not marked.
- Inactive bottom-nav icons are too faint; brightening them nearly matches the
  active one, so it needs a different approach.
- Calendar input box depth varies by device; workable, not solved.
- `.jenc` encryption machinery still present across ~7 files, unused.
- `copyright_license_text_official` has no reader and still names Apache 2.0.

---

## 7. Working with this user

- They test on device every round, report precisely, and are usually right about
  what they saw. **When they say something didn't change, believe them and look
  for a second cause** rather than assuming they didn't build. Twice the real
  answer was elsewhere: a "not working" spinner was a pre-existing `firstFire`
  re-arm bug, and an "unchanged" contributors list meant the *files in the tree*,
  not the screen.
- They value predictability over cleverness. State that resets — collapsed
  categories, the spinner returning to General, view mode not remembered — is a
  deliberate preference, not an oversight.
- They are blunt and fair. When they asked why I was sending 42 MB instead of a
  patch, the honest answer was that I'd already identified the fix myself and
  not applied it. Own that directly; don't over-apologise.
- Explain the *why* behind a fix. Several bugs had non-obvious causes — a bare
  `<shape>` used as a view background is stretched to view bounds, which is why
  a day highlight was an oval; a green that "never worked" was a debug-only
  resource override. Those explanations landed better than the fixes.

---

## 8. Gotchas, collected

| Symptom | Cause |
|---|---|
| XML "invalid token" at a column | `--` inside an XML comment. Illegal. Hit three times |
| A circle renders as an oval | A bare `<shape>` as a view background stretches to bounds. Use a layer-list with a fixed size and `gravity="center"` |
| Calendar colours ignored | Stock `CalendarView` ignores them on API 21+ |
| Spinner change does nothing | `firstFire` guard re-arms on every `bindCategories()`; `setSelection` to the same index fires no event |
| A keyboard eats a view | `adjustResize` squeezes a weighted child first. Use a fixed height inside a `ScrollView` |
| `R.raw.x` missing from the tree | Generated by `copyRepoFiles` at build time |
| Duplicate resource name | Hard AAPT error, not a warning |
| A "new" check finding after a rename | `check-vs-base.sh` compares basenames for this reason |
