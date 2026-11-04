# Autumn

A journalling and archiving app for Android. Capture an entry in seconds, find it years later by **when** it happened.

Autumn is not a planner and not a reminder app. It is a memory aid: log a titled note into the right year, month and category, then browse it back in chronological order.

**Status:** early, under active development. Expect rough edges.

## Why

Folders are good at hierarchy and bad at time. Autumn keeps the sense of *when* that makes an archive worth having:

* **Fast capture.** Pick a date, pick a category, type a title, submit. The title often carries the whole entry.
* **Creation order, not edit order.** An entry written in March stays in March, however often you revise it afterwards. This is the thing ordinary note apps get wrong.
* **Local and offline.** Plain Markdown files in a folder you own. No account, no sync service, no lock-in.

## How it stores things

```
/Documents/Autumn/
  2026/
    11_November/
      Travel/
        A long walk up Cat Bells.md
```

One home folder, set in exactly one place. Year and month folder names are pinned to English so they stay stable if you change your phone's language.

Each note begins with a date stamp as its first line. That stamp is the durable record of when the entry was created — it survives rename, move, copy, reinstall and moving to a new phone, because it lives inside the file rather than in an index beside it.

## Features

* **Calendar capture** — pick any date, including backdating, and the folders are created for you.
* **Folder tree** — browse Year → Month → Category → Note in creation order.
* **Markdown editor** — syntax highlighting, search and replace, a customisable action bar.
* **Date formatting** — insert timestamps in a format you choose.
* **Categories** — add and remove your own.

## Building

Requires Android Studio and the Android SDK. There are no product flavours; the variants are `debug` and `release`.

```bash
git clone https://github.com/PIXEL-PEN/markor.git
cd markor
./gradlew assembleDebug
```

Debug builds install under their own application id and read `/Documents/Autumn (dev)`, so a development build cannot touch a real archive.

## Licence

**Apache-2.0.** See `LICENSE.txt` for the full text and `NOTICE` for attribution and a summary of modifications.

Autumn is a modified fork of [Markor](https://github.com/gsantner/markor), Copyright 2017-2025 Gregor Santner, and is not affiliated with or endorsed by that project. Please report issues with Autumn here, not upstream.

## Acknowledgments

Built on Markor and its community.

The folder-tree idea began with [@harshad1](https://github.com/harshad1)'s HTML calendar prototype — see the [original discussion](https://github.com/gsantner/markor/discussions/2278).
