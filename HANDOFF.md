# Autumn — Handoff

Current as of commit `b1201ab9` (13 Nov 2026). Replaces the earlier handoff entirely.
Read sections 1–4 before touching code. Section 8 is the list of things that have
already cost an evening each; read it before you think you have found something new.

---

## 1. What this is

**Autumn** (`net.pixelpen.journal`) is a journaling / archiving / timeline Android app
rebuilt from a Markor fork. Repo: `https://github.com/PIXEL-PEN/autumn` (renamed from
`markor` on 13 Nov 2026; old URL redirects). Branch: **`autumn`**, the only branch and the
default. Markor's `master` and `markor-plus-v1.2.6` branches are deleted; the fork point
`c4cd8a82` is an ancestor of `autumn` and is the baseline for the static checks.

Archive layout on the device: `<home>/<yyyy>/<MM_MMMM>/<Category>/note.md`, with
`<home>/Images/<yyyy>/` for attachments. Two installs coexist:

| Build   | applicationId               | Label        | Home folder                 |
|---------|-----------------------------|--------------|-----------------------------|
| debug   | `net.pixelpen.journal.dev`  | Autumn (dev) | `/Documents/Autumn (dev)`   |
| release | `net.pixelpen.journal`      | Autumn       | `/Documents/Autumn`         |

Both are signed with the debug key for now. The user field-tests on **debug** by
default and says when release should get a change too.

### Hard constraints

- **Strictly offline.** No `INTERNET` permission, no outbound links, nothing that needs
  a network. Do not add any.
- The user's email identifies them only. Never put it in a request to any service.
- **Claude cannot build, push, or reach the phone.** No Android SDK in the session, no
  GitHub credential. The user builds, installs and pushes. Everything is delivered as
  commands to paste and a git bundle to fetch (section 4).
- General is the protected category: always exists, cannot be deleted.

---

## 2. Toolchain and build (new laptop, 13 Nov 2026)

Linux Mint. Android Studio at `/opt/android-studio` (the "Rabbit" release). Clone at
`~/StudioProjects/autumn`. Phone: Xiaomi 2506BPN68G (MIUI), USB debugging.

Pinned by the project and **must stay in step**: AGP `8.8.0`, Gradle `8.10.2`,
compileSdk/targetSdk `35`, build-tools `35.0.0`, minSdk `24`, Java source/target `1.8`.
Gradle 8.10.2 runs on JDK 8–23 only; Studio's bundled JDK is newer, so the **Gradle JDK
is set to 21** in Studio settings (it offered "Use JVM 21" on first sync — that was
the fix). Lifting that ceiling means moving Gradle to 8.14+ and probably AGP with it:
a separate afternoon, not something to bolt onto other work.

Build and install, debug only (the default):

```
cd ~/StudioProjects/autumn
./gradlew assembleDebug --console=plain 2>&1 | tail -3 && \
adb install -r "$(ls -t app/build/outputs/apk/debug/*.apk|head -1)"
```

Release: `assembleRelease` and `.../release/*.apk`. Both can be installed side by side.

### Landmine: the APK name changes daily

`versionCode = yyMMdd` and `archivesBaseName = applicationId-v<versionCode>-<versionName>`,
so the APK is e.g. `net.pixelpen.journal-v261113-1.2.5-debug.apk` and a different name
tomorrow. **Never hardcode an APK filename.** The `ls -t ... | head -1` above is the
reliable form. Note the debug APK's *filename* has no `.dev` suffix; only the installed
package does.

### Landmine: MIUI and adb

`INSTALL_FAILED_USER_RESTRICTED` means MIUI refusing a **new** package over adb; updates
to an installed package are fine. First install of a build type: sideload the APK from
the phone's file manager. Also: the phone has a Second Space (user 10); apps installed in
user 0 don't appear there. `adb shell pm list packages | grep pixelpen` settles "is it
installed".

### Landmine: res/raw is partly generated

`copyRepoFiles` in the root `build.gradle` copies `ROOT_TO_RAW_COPYFILES`
(`["LICENSE.txt"]`) into `res/raw`, lowercased. Do not hand-edit those.

---

## 3. Verification — read this before writing code

There is no compiler in the Claude session. Everything below exists because a broken
commit costs the user a build cycle.

`tools/check/` — the static checks, now **in the repo** (they used to live only in the
session container, which is lost when a chat ends):

| Script | What it does |
|---|---|
| `check.py` | Static checks on the tree: brace/structure balance, field and type sanity, unresolved resources, duplicate resource names, empty resources, unthemed drawable fills, DayNight theme parent, untinted black icons, `--` inside XML comments. Noisy on inherited Markor code, by design. |
| `check-vs-base.sh` | Runs `check.py` at the pinned base commit `c4cd8a82` and at HEAD and reports only the **difference**. Refuses to compare if a check crashed. |
| `check-removed-methods.py` | Compares method declarations at the base against now for watched classes; flags removed methods still called. |

`tools/treetest/run.py` — lifts `FolderTreeFragment`'s expansion methods **verbatim** on
every run and executes them against a temporary archive. JDK only, no SDK, seconds.
It caught a real regression once already. Run it after any change to the Tree.

### The gate

```
bash tools/check/check-vs-base.sh 2>&1 | grep -q "NO NEW PROBLEMS" && echo OK
python3 tools/treetest/run.py | tail -1
```

**Grep for `NO NEW PROBLEMS`. Never gate on `check.py`'s exit code** — it is always
non-zero because the base has ~45 pre-existing findings. Current baseline: 45 at base,
44 at HEAD.

### What the checks cannot do

They cannot compile. They have missed: a method referenced from a lambda that was later
deleted; an import removed while still used (the grep that "confirmed" it was truncated);
a `@Override` stacked on another because an edit was anchored on the signature line while
the annotation sat above it (**three times** — always anchor edits to include the
annotation, and run the gate, which now catches this). When a build fails on the user's
machine, the fix is the first thing in the next reply, not the last.

### Never invent a resource name

Every `R.color`, `R.string`, `R.id`, `R.drawable` used must be grepped for in `res/`
before use. The checks flag unresolved ones but only if they run; a typo in a check can
mask it.

---

## 4. Delivery

Incremental **git bundles**, fetched by the user into their clone, plus the exact commands.

```
# cut (in the session)
git branch -f autumn HEAD
git bundle create /mnt/user-data/outputs/autumn-<topic>-<hash>.bundle <prereq>..autumn
git bundle list-heads <file>      # MUST show refs/heads/autumn, not only HEAD
```

```
# apply (user pastes)
cd ~/StudioProjects/autumn
git fetch ~/Downloads/autumn-<topic>-<hash>.bundle "refs/heads/*:refs/remotes/bundle/*" --force && \
git merge --ff-only <hash> && git log --oneline -1
```

Rules, each learned the hard way:

- **The prerequisite must be a commit the user actually has.** Ask for
  `git log --oneline -1` if unsure. Three bundles once silently delivered nothing because
  the range form `<prereq>..HEAD` writes a ref named `HEAD`, which `refs/heads/*` does not
  match; the fetch "succeeded" with zero refs and the merge said "not something we can
  merge". Always bundle `..autumn` (a branch) and check `list-heads`.
- **Unique filename per cut.** Re-issuing a name makes the browser save `name (1)` and
  the user runs the stale one. Hash in the name, plus a topic word.
- **No wildcard deletes next to fresh files.** `rm ~/Downloads/apply-*.sh` once deleted
  the file just sent.
- **Bare commands, not wrapper scripts.** The user asked for this explicitly after
  scripts hid failures. One paste block, `&&`-chained so it stops at the first failure.
- Debug-only install by default; say when release should get it too.
- After a successful apply: `git push origin autumn --follow-tags`. GitHub auth on the
  new laptop is `gh` (Ubuntu's 2.4.0 — `gh auth setup-git` does not exist in it; the
  helper is set manually: `git config --global credential.helper '!gh auth git-credential'`).

---

## 5. Architecture, and why

**Navigation**: bottom tabs Calendar / Tree / Year / Quicknote (`MainActivity`,
`main__bottom_nav.xml`). Overflow has Home (opens the archive in the system file manager,
i.e. *leaves the app*), Settings, Search (its own screen, `SearchActivity`, field in a
card above centre).

**Capture (Calendar)**: `CalendarFragment`. Category spinner reads
`CategoryManager.listCategories()` = folders found on disk ∪ stored custom list; no
"Add" row in it — adding lives in Manage categories. `selectedCategoryDir()` resolves
to the newest folder holding the category when this month has none; the dropdown and
Browse both use it. Day card numeral 61sp.

**Categories**: `CategoryManager`. `rename/delete/merge` all update the stored list
(`saveCustom`) — they did not, for a long time. Defaults are seeded **once per install**
by a flag (`seedDefaultsOnce`), not on emptiness, because General is always present so
the list is never empty. Seeds: General, Medical-Health, Reference, Ideas, Projects,
Social, Key Events, Finance. `ManageCategoriesDialogFragment` counts notes on a
background thread; it captures the application context *before* the thread starts.

**Tree**: `FolderTreeFragment` + `TreeStore` (cached scan, rebuilt per view) +
`FolderTreeAdapter`. Expansion is a set of absolute paths; `seedExpansion` opens the
chain for a requested category **only if the folder exists on disk**, walking up to the
nearest existing ancestor otherwise (a recorded-but-absent path is undrawable and looked
exactly like broken expansion). A newly opened category is scrolled to a fifth of the
way down. An open category's label gets a filled band (`setBackgroundColor`, never
`setBackgroundResource`). `.skip-scan` hides a folder from the Tree; `.nomedia` no longer
does (Autumn plants `.nomedia` in `Images/` and `Files/` itself, so those folders are now
visible under "Other files" — side effect, see open items).

**Year**: `YearOverview` builds its category list from folders present *in that year*;
empty seeded categories do not appear there by design. Default category General.
Highlight slots and growth prompt use `year_prompt` text over a `year_slot_empty` 1dp dash.

**Settings**: one Editing screen (Text / Lists and action bar / Syntax highlighting),
General (Theme at top), About. `preferences_master.xml`.

**Rendering (view mode)**: `TextConverterBase.convertMarkupShowInWebView` writes the HTML
to the app's **cache** (never the archive), injects `<base href="file://<note dir>/">`,
loads it by URL, and **inlines local images as `data:` URIs** (16 MB budget per note).
Three verified failures led here: the WebView would not load `file://` images from a
`loadDataWithBaseURL` document (opaque origin), nor relative, nor absolute, nor from a
real-file document. Data URIs are the only thing that rendered. `http(s)` sources are left
alone — no network permission.

**Theme**: `ApplicationObject.onCreate` calls `applyAppTheme()` **before any Activity
exists**. That ordering is the whole dark-mode fix: AppCompat can only apply a forced
night mode robustly (`createConfigurationContext`) if it knows the mode at
`attachBaseContext`; set later, it mutates Resources in place and the platform reverts
that on resume from another app and when a WebView starts. `AppSettings.colorContext()`
gives app-context colour lookups their own override. `AppTheme.Unified` sets
`forceDarkAllowed=false` (MIUI force-darkens light apps per app otherwise). Debug builds
log `AutumnTheme` on every resume — `adb logcat -s AutumnTheme` — so a regression is
measured, not theorised. `values-night/` has 43 variants; `tree_*`, `year_*`,
`tree_open_*`, `surface_card` are themed.

**Launcher icon**: nothing in the app changes component enabled-state any more.
`applySpecialLaunchersVisibility` toggled an alias on every launch, which fired a
package-changed broadcast seconds after each install and MIUI dropped the icon. Deleted.

---

## 6. Open items

### Known gaps, rough priority

1. **Archived HTML pages don't render** (saved web pages in the archive). Unexplored.
   Check first whether `.html` even reaches `TextConverterBase` or is treated as text —
   if view mode shows raw source or blank, that's a different path from the image fix.
2. **Share-into is unreachable.** `OpenShareIntoActivity` exists but has no manifest
   entry, so other apps cannot share into Autumn. Needs an `<activity>` with the SEND
   filters. Small, deliberate change; test the icon survives after.
3. **`Images/` and `Files/` now show in the Tree** under Other files, since `.nomedia`
   stopped hiding folders. Decide: hide those two by name, or leave visible.
4. **Category Manager six-point spec** (user's words, 9 Nov): seeded categories ✓;
   General protected ✓; rename/delete/add ✓; delete offers move-or-delete with an
   "are you sure" (verify the second branch's wording); manual folders picked up ✓;
   collapse/expand ✓. User later said "sufficiently intact" — confirm before spending.
5. **HTML export** of Year overview / whole archive (Timeline HTML exists:
   `HtmlExport.exportTimeline`). User said "I'll sleep on it"; design not settled.
6. Guide: sections still unwritten; tagline provisional ("You remember what happened.
   Autumn remembers when."). No term "pill" anywhere user-facing.
7. First-run screen replacing the bare storage-permission prompt.
8. Repo hygiene: `.github/ISSUE_TEMPLATE/` (four Markor templates), `CHANGELOG.md`,
   `build.gradle.ui-polish-backup`, stock `drawable/ic_launcher_background.xml` (unused;
   the adaptive icon uses `@color/`). `settings.gradle` already says `Autumn`.
9. Parked: GPL-3 headers on new files; full GPL text; replace `PIXEL-PEN` placeholder
   in the copyright line; OFL/Ubuntu font licence texts; `.jenc` removal; `MonthGrid`
   unused; API 24–25 has no raster fallback for 7 icon names.
10. Launcher-icon WEBP conversion was uncommitted on the old laptop and is lost. The
    PNG set in the repo is what the icon fix was verified against.

### Toolchain debt

Gradle 8.10.2 / AGP 8.8.0 / Java 8 target. Studio warns on all three. Upgrade as one
deliberate job with a build at each step, not opportunistically.

---

## 7. Working with this user

- Field-tests on the phone between replies; reports symptoms precisely. **Their
  observations have cracked every hard bug** ("absolute fails too", "only a note with an
  image triggers it", "System+Dark works"). Ask for the one measurement that decides it
  rather than offering a theory; they will run it.
- Warn before anything risky or long; do risky things one at a time.
- Short replies. No recaps, no "I'll now…", no restating their message. Lead with the
  finding or the command.
- Placeholders get typed literally (`<your clone>` was). Use real paths:
  `~/StudioProjects/autumn`, `~/Downloads/`. Say which lines are commands and which are
  output to look for (a log line in a code block got pasted into bash once).
- `!` inside double quotes trips bash history expansion; single-quote or avoid.
- No jargon in UI text ("pill").
- They are on a Pro plan with weekly limits and watch usage. Long chats are expensive
  per message; prefer a fresh chat with this file, and a lighter model for shell work.
- After "we'll resume tomorrow" or similar, stop; don't add offers.

---

## 8. Gotchas, collected (each cost real time)

**Android / AppCompat**
- Forced night mode must be set in `Application.onCreate`, before any Activity. (§5 Theme.)
- `Resources.updateConfiguration` rewrites the *whole* configuration incl. `uiMode`;
  `setAppLocale` used to call it on every activity creation. Now only when a language is
  chosen, on a copy, preserving night bits.
- The application context never carries AppCompat's night override; colours read
  through it come back in the system palette. Use an activity/view context or
  `colorContext()`.
- `loadDataWithBaseURL` with a non-http base ⇒ opaque origin ⇒ no `file://` subresources.
- `setBackgroundResource` **replaces** the view's padding with the drawable's.
- `android:tint` on a preference icon is wrong; `GsPreferenceFragmentBase` already tints.
- A `<solid android:color="@android:color/white">` in a drawable is invisible to
  `values-night`. Use `@color/` and give it a night variant (unless it's a tint base).
- `MODE_NIGHT_AUTO` is deprecated; `pref.contains("dark")` matches `"dark-black"`.
- A fragment's `getString`/`getResources` on a background thread throws once detached;
  capture a context first. Guards placed *after* the throwing call are not guards.
- `setComponentEnabledSetting` on an undeclared component throws (swallowed); on a
  declared one it broadcasts package-changed, which MIUI's launcher answers by dropping
  the icon.
- Markor's `GsFileUtils.relativePath` fails across `/sdcard` vs `/storage/emulated/0`
  (same folder, different strings) and emits absolute paths. `ArchiveImages.relativeLink`
  canonicalises.
- Build type `resValue` strings and `archivesBaseName` are evaluated from `defaultConfig`.

**XML / resources**
- `--` inside an XML comment is a hard AAPT error. (Six times.)
- Duplicate `<string name>` is a hard AAPT error; the checks catch it.
- 65 zero-byte PNGs inherited from markor-plus broke only release (debug doesn't crunch).
- `strings.xml` was emptied by a machine crash mid-build once (ext4 journalled metadata,
  lost data). Keep commits small and pushed.
- A layout weight only works if the parent is bounded.

**Process**
- A truncated grep led to a wrong conclusion twice. Never conclude from `head`.
- Edits anchored on a signature line with `@Override` above it ⇒ stacked annotations.
- `check.py`'s exit code is meaningless; grep `NO NEW PROBLEMS`.
- Shallow clones can't compute merge-bases; deepen before pinning hashes.
- Bundles from a range `..HEAD` carry no branch ref. Bundle `..autumn`.
- A theory that explains one symptom and not the mirror image is wrong. Dark-on-light
  and light-on-dark had *different* causes (late override vs Force Dark); both needed.
