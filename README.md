# Autumn

A journalling and archiving app for Android. Capture an entry in seconds, find it years later by **when** it happened.

Autumn is not a planner and not a reminder app. It is a memory aid: log a titled note into the right year, month and category, then browse it back in chronological order.

**Status:** early, under active development. Expect rough edges.

## Why

Folders are good at hierarchy and bad at time. Autumn keeps the sense of *when* that makes an archive worth having:

* **Fast capture.** A date, a category, a title. The title often carries the whole entry — they run to 250 characters, so a list of them reads as a journal without opening anything.
* **Creation order, not edit order.** An entry written in March stays in March, however often you revise it. This is the thing ordinary note apps get wrong.
* **Local and offline.** Plain Markdown files in a folder you own. No account, no sync service, no lock-in.

## Strictly offline

Autumn holds no `INTERNET` permission. Not "doesn't phone home" — *cannot*. Every outbound link was removed from the app, the renderer assets are bundled rather than fetched, and the build ships one language rather than pretending to thirty.

## How it stores things

```
/Documents/Autumn/
  2026/
    11_November/
      Travel/
        A long walk up Cat Bells.md
  Quicknotes/
    2026/
      2026-11 November.md
  Images/2026/     Files/2026/
```

One home folder, set in exactly one place. Year and month folder names are pinned to English so they stay stable if you change your phone's language.

Each note begins with a date stamp as its first line. That stamp is the durable record of when the entry was created — it survives rename, move, copy, reinstall and moving to a new phone, because it lives inside the file rather than in an index beside it.

## The four screens

**Calendar** — a single day card showing the date you are filing against; tap it to choose another. Below it, a category and a title. The **+** writes the note and opens it; **Browse** jumps to that category in the tree.

**Tree** — Year → Month → Category → Note, newest first. Long-press to rename, move or delete. Files that are not journal entries appear under *Other files*, apart from the journal years.

**Year** — a year on one page: open-ended highlights, a personal-growth section, then every month's entries. Exports the whole thing as a self-contained HTML timeline with the pictures inside it.

**Quicknote** — the yellow bolt. Trivia that does not deserve a file of its own: the shop, the salesperson, the thing you want next week and never again. A month's worth share one document, newest first, no category. Long-press to read the month without writing.

## Other things it does

* **Search** across the whole archive. `blue fridge` wants both words anywhere in a note, `"blue fridge"` wants the phrase, `/fridges?/` is a regular expression. Results show the matching line and open the note at it.
* **Pictures** in one place, organised by year, whichever screen you add them from. Tap any thumbnail for full screen, swipe between them.
* **Backup** the whole archive to a single zip, anywhere.
* **Guide** in the overflow menu.

## Building

Requires Android Studio and the Android SDK. There are no product flavours; the variants are `debug` and `release`.

```bash
git clone https://github.com/PIXEL-PEN/markor.git
cd markor
./gradlew installDebug
```

Debug builds install under their own application id and read `/Documents/Autumn (dev)`, so a development build cannot touch a real archive.

## Licence

**GPL-3.0.** Copyright (C) 2026 PIXEL-PEN. See `LICENSE.txt`.

Autumn is a derivative work of [Markor](https://github.com/gsantner/markor), Copyright 2017-2025 Gregor Santner, licensed under Apache 2.0 — which permits redistribution under the GPL. Markor's notice is retained in `LICENSE.txt` and its licence text, with every third-party licence the app depends on, is reachable in the app under Settings → About.

Autumn is not affiliated with or endorsed by Markor. Please report issues with Autumn here, not upstream.

## Acknowledgments

Built on Markor and its community.

The folder-tree idea began with [@harshad1](https://github.com/harshad1)'s HTML calendar prototype — see the [original discussion](https://github.com/gsantner/markor/discussions/2278).
