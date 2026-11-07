# Autumn

## You remember what happened. Autumn remembers when.

Most people keep the events and lose the order. You know it was a good year, you
could list most of what was in it, and you could not say what came before what.
Given long enough the whole thing settles into an undifferentiated *back then*.

That is the particular failure this app is built for. It is not a planner and
not a reminder: it takes what you write, files it under the day it belongs to,
and gives the sequence back when you want it.

Everything is kept as ordinary files on your phone, in folders you can read
without this app and without any other program. Nothing you write leaves the
device — Autumn has no permission to use the network, so it cannot send your
journal anywhere even by accident.

## Two ways to write

There are two buttons on the Calendar screen, and they do genuinely different
things. Most of learning Autumn is knowing which one you want.

**The plus button** makes a journal entry. The card at the top of the Calendar
screen shows the date you are filing against — tap it to choose another, which is
how you back-date something you forgot. Pick a category, type a title, and the
note opens so you can write. The entry gets a file of its own, filed under its
year, month and category.

The colour of the card's header follows the month, so after a while it tells you
roughly where you are in the year before you read the word.

A title can run to 252 characters, which is long enough to be a whole thought.
That is deliberate: it means you can often write the entire entry as the title
and simply back out of the editor without typing anything inside it. Those
entries read as a list you can skim without opening anything.

**The lightning button** makes a quicknote. No category, no file of its own, no
decisions. It goes straight into a single document for the current month, newest
at the top, and that document opens so you can see it landed.

> If you are not sure which to use: if you would mind losing it in a year, use
> the plus. If you would not, use the lightning.

## Categories, and whether you need them

A category is a folder with a name you chose: Work, Family, Health, Travel,
whatever the shape of your life is. Every journal entry goes in one.

Categories are what make a journal searchable years later, and what the Year
screen is built on. They are worth the small effort at the time of writing.

But they are an effort, and not everything deserves one. You bought a fridge at
a shop across town, the salesperson was unusually good, you may want the shop's
name next month and never again. That is not a journal entry, and filing it like
one is a chore that will put you off writing anything.

That is what quicknotes are for. They are the place for things that are useful
now and dispensable later, so the categories stay meaningful.

You can manage your categories from Settings. Renaming one moves every note
inside it; deleting one asks where its notes should go.

## The Tree

The Tree shows your whole archive: categories, then years, then the notes
themselves, newest first.

- Tap a note to read it.
- Long-press a note to rename, move or delete it.
- Categories stay closed until you open one, and close again when you leave.

Files that are not journal entries — your quicknotes, imported documents —
appear under **Other files**, kept apart from the journal years.

## The Year screen

The Year screen is the long view: a year on one page.

**Highlights** are the handful of things you want to remember about the year.
There is no limit; a new empty slot appears as you fill them. They sort by the
month they are about, not the day you wrote them.

**Personal growth** is a single open field for achievements, milestones, proud
moments, aspirations — the things that do not belong to one date.

Below those, every month lists its entries, newest first. Tap any one to open
it.

You can add pictures to a year from the row under **Images**, which starts
closed.

## Reading mode and writing mode

Notes open in reading mode when you are browsing, and in writing mode when you
came to write. The pencil in the top bar switches between them at any time.

So tapping a note in the Tree gives you a clean rendered page, while the plus
button drops you straight into the editor with the cursor ready.

## Pictures and files

Pictures live in one place, `Images`, organised by year, and attachments in
`Files`. It does not matter which screen you add a picture from — it goes to the
same place, so nothing is scattered.

Tap a thumbnail anywhere to see it full screen, and swipe to move between
pictures.

## Searching

The magnifier searches every note in the archive. Results show the matching line
and its category, and tapping one opens the note at that line rather than at the
top.

Three ways to ask:

- **blue fridge** finds notes containing both words. They do not have to be near
  each other, or even on the same line: a note titled "Fridge" with "the blue
  one" in the body is a match. More words narrow the search, never widen it.
- **"blue fridge"** in quotes finds that phrase exactly as written.
- **/fridges?/** between slashes is a regular expression, if you know them. A
  half-typed one is treated as ordinary text rather than finding nothing.

Searches ignore capitals, and a result lands on the line that matches the most of
what you asked for.

## The Timeline

This is the reason for the rest of it.

From the overflow menu, **Timeline** builds your whole archive as a single
scrolling web page: decades, then years, then months down a gutter, with every
entry in order and the pictures sitting where they happened.

Writing one note at a time, you never see the shape of it. The Timeline is where
a few hundred notes stop being a folder and become a life you can read in one
pass — and where the thing that blurs first, the order, is the one thing it
cannot lose.

The file is self-contained, pictures and all. It opens on any computer without
Autumn installed, it prints, and it will still open in twenty years, which is
not something you can say of most things you could export to.

Build one at the end of a year. It is the clearest view of that year you will
get, and it costs nothing to keep a copy somewhere other than your phone.

## Where your files are

Everything is under `Documents/Autumn` on your phone:

- `<Year>/<Month>/<Category>/` — journal entries
- `Quicknotes/<Year>/` — one document per month
- `Images/<Year>/` and `Files/<Year>/` — pictures and attachments

They are plain Markdown text files. If this app disappeared tomorrow, every word
you wrote would still be readable in any text editor on any device. That is the
point of storing it this way.

**Backup** in the overflow menu copies the whole archive into a single zip, to
anywhere you choose.
