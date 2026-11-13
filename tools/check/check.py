#!/usr/bin/env python3
"""
Pre-send checks for the Autumn tree, run from the repo root.

I cannot compile here: Maven and PyPI are both refused by the gateway, so there
is no android.jar and no Java parser available. These checks are what can be
done without one. They catch, in rough order of how often they have actually
bitten:

  * resource references that do not resolve (R.string / R.drawable / R.color /
    R.layout / R.id), in Java and in layouts
  * resource XML that is not well-formed -- including "--" inside an XML
    comment, which is illegal and which AAPT rejects
  * types used without an import that resolves
  * unbalanced braces or parens, and stacked @Override

They do NOT do scope analysis, which is the one class of bug that has got past
me twice (a local left behind in one method while its uses moved to another).
javac would catch it, but it bails at import resolution long before flow
analysis when the Android types are missing. The guard against that is
procedural: no editing Java by line number, and read back any structural change.
"""
import glob
import io
import os
import re
import sys

JAVA_LANG = set("""String Integer Long Double Float Boolean Byte Short Character Object Class
Exception RuntimeException Throwable Error IllegalArgumentException IllegalStateException
NumberFormatException NullPointerException UnsupportedOperationException Math System Thread
Runnable Comparable Iterable CharSequence StringBuilder Override Deprecated SuppressWarnings
SafeVarargs FunctionalInterface Void Enum Number Process ClassCastException Runtime
OutOfMemoryError SecurityException Package ArithmeticException IndexOutOfBoundsException
ArrayIndexOutOfBoundsException StringIndexOutOfBoundsException CloneNotSupportedException
InterruptedException ThreadLocal StackTraceElement Appendable AutoCloseable Cloneable
Readable StringBuffer ClassLoader Record AssertionError""".split())

# Declared by AndroidX / Material, not by this app.
LIBRARY_RES = {"appbar_scrolling_view_behavior", "copy"}

SRC_ROOTS = ["app/src/main/java", "app/thirdparty/java"]
RES = "app/src/main/res"

fails = []


def note(msg):
    fails.append(msg)
    print("  FAIL " + msg)


def strip_code(src, keep_newlines=True):
    """
    Blank out string literals, char literals and comments in one pass.

    Doing these as separate regex passes is wrong and was quietly breaking the
    brace count: strip strings first and an apostrophe or a lone quote inside a
    comment ("// don't") opens a literal that runs to the next quote somewhere
    further down the file, eating whatever braces lie between. Strip comments
    first and a "//" inside a string URL eats the rest of the line. They have to
    be recognised together, in order, which is what this does.

    Newlines are preserved so line numbers still mean something.
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            q = c
            out.append(" ")
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == q:
                    i += 1
                    break
                if src[i] == "\n" and keep_newlines:
                    out.append("\n")
                i += 1
            out.append(" ")
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                if src[i] == "\n" and keep_newlines:
                    out.append("\n")
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def res_names(kind):
    """Declared resource names of one kind."""
    out = set()
    if kind in ("string", "color"):
        for p in glob.glob(f"{RES}/values*/*.xml"):
            txt = open(p, encoding="utf-8").read()
            for m in re.finditer(r'<(\w+)[^>]*\bname="([^"]+)"', txt):
                tag = m.group(1)
                if (kind == "string" and tag in ("string", "plurals", "string-array")) or \
                   (kind == "color" and tag == "color"):
                    out.add(m.group(2))
    elif kind == "drawable":
        for p in glob.glob(f"{RES}/drawable*/*") + glob.glob(f"{RES}/mipmap*/*"):
            out.add(os.path.splitext(os.path.basename(p))[0])
        for p in glob.glob(f"{RES}/values*/*.xml"):
            txt = open(p, encoding="utf-8").read()
            for m in re.finditer(r'<(?:drawable|color)[^>]*\bname="([^"]+)"', txt):
                out.add(m.group(1))
    elif kind == "layout":
        for p in glob.glob(f"{RES}/layout*/*.xml"):
            out.add(os.path.splitext(os.path.basename(p))[0])
    elif kind == "raw":
        for p in glob.glob(f"{RES}/raw/*"):
            out.add(os.path.splitext(os.path.basename(p))[0])
        # res/raw is partly generated. build.gradle's copyRepoFiles task copies these from
        # the repo root at build time, lowercasing as it goes, which is how R.raw.contributors
        # and R.raw.changelog resolve without any such file being committed. Counting them as
        # declared only when the source file is actually present means a real typo still
        # fails, and a deleted CONTRIBUTORS.md shows up here rather than in the build.
        for f in ("README.md", "CHANGELOG.md", "CONTRIBUTORS.md",
                  "LICENSE.txt", "LICENSE.md", "LICENSE"):
            if os.path.isfile(f):
                out.add(os.path.splitext(f.lower())[0])
    elif kind == "id":
        for p in glob.glob(f"{RES}/**/*.xml", recursive=True):
            txt = open(p, encoding="utf-8").read()
            out |= set(re.findall(r'@\+id/(\w+)', txt))
        for p in glob.glob(f"{RES}/values*/*.xml"):
            txt = open(p, encoding="utf-8").read()
            # Either attribute order: type before name, or name before type.
            out |= set(re.findall(r'<item[^>]*\btype="id"[^>]*\bname="(\w+)"', txt))
            out |= set(re.findall(r'<item[^>]*\bname="(\w+)"[^>]*\btype="id"', txt))
    if kind == "color":
        # Colour state lists are their own files in res/color and the name is the file name.
        # The scan above only reads <color name="..."> out of values/, so every @color
        # pointing at a state list looked unresolved.
        for p in glob.glob(f"{RES}/color*/*.xml"):
            out.add(os.path.splitext(os.path.basename(p))[0])

    return out


def check_resources(java_files):
    print("resource references")
    declared = {k: res_names(k) for k in ("string", "color", "drawable", "layout", "id", "raw")}
    for p in java_files:
        # Comments must go first: several upstream files carry example usage in a
        # javadoc block, referencing R.layout.activity_main and friends that only ever
        # existed in the snippet's own imaginary project.
        s = strip_code(open(p, encoding="utf-8").read())
        for kind in declared:
            # android.R.* is the framework's, not ours.
            for m in re.finditer(r'(?<!android\.)\bR\.%s\.(\w+)' % kind, s):
                if m.group(1) not in declared[kind]:
                    note(f"R.{kind}.{m.group(1)} -> {os.path.basename(p)}:"
                         f"{s[:m.start()].count(chr(10)) + 1}")
    for p in glob.glob(f"{RES}/**/*.xml", recursive=True):
        s = open(p, encoding="utf-8").read()
        for kind in ("string", "color", "drawable", "layout", "raw"):
            for m in re.finditer(r'"@%s/(\w+)"' % kind, s):
                if m.group(1) in LIBRARY_RES:
                    continue
                if m.group(1) not in declared[kind]:
                    note(f"@{kind}/{m.group(1)} -> {os.path.relpath(p, RES)}")
    if not fails:
        print("  ok")


def check_untinted_black_icons():
    """A black vector icon with no tint.

    ic_*_black_24dp is literally black artwork. On a light page that is correct and on a dark
    one it is a black icon on a dark background. The category gear and the pencil and bin in
    Manage categories were all like this and all invisible at night.

    An icon that carries android:tint or app:tint is fine whatever the tint resolves to,
    because a tint can have a night variant and the artwork cannot.
    """
    import os
    print("black vector icons are tinted")
    before = len(fails)
    pat = re.compile(r"<(?:ImageView|ImageButton|androidx\.appcompat\.widget\.AppCompatImageView)\b[^>]*?/>", re.S)
    for p in sorted(glob.glob(f"{RES}/layout*/*.xml")):
        src = open(p, encoding="utf-8").read()
        for m in pat.finditer(src):
            tag = m.group(0)
            if "_black_24dp" in tag and "tint" not in tag:
                icon = re.search(r'android:src="@drawable/([a-z_0-9]+)"', tag)
                note(f"{os.path.basename(p)}: {icon.group(1) if icon else 'an icon'} is black "
                     f"artwork with no tint, so it stays black in dark mode.")
    if len(fails) == before:
        print("  ok")


def check_daynight_theme():
    """If there is a night palette, the app theme has to be able to use it.

    The -night resource qualifier and the theme parent are independent. The qualifier follows
    the uiMode configuration, so values-night applies whatever the parent says, and every
    screen built from @color/ resources goes dark either way. Screens built from theme
    attributes do not: a preference title reads ?android:textColorPrimary, which a Light
    parent answers in black, on top of the dark background the qualifier has already drawn.

    That is exactly what happened. The parent was left at Light while values-night was
    written, and Settings was the only screen in the app that showed it.
    """
    print("app theme can reach values-night")
    import os
    if not os.path.isdir(f"{RES}/values-night"):
        print("  ok (no night palette)")
        return
    before = len(fails)
    for p in glob.glob(f"{RES}/values/styles.xml"):
        for m in re.finditer(r'<style name="AppTheme\.Unified"\s+parent="([^"]+)"', open(p, encoding="utf-8").read()):
            if "DayNight" not in m.group(1):
                note(f"styles.xml: AppTheme.Unified descends from {m.group(1)}, which cannot "
                     f"resolve values-night. Theme attributes stay light while @color stays dark.")
    if len(fails) == before:
        print("  ok")


def check_unthemed_drawable_fills():
    """A <solid> fill that no theme can reach.

    "@android:color/white" and bare hex in a drawable are invisible to values-night, so the
    shape keeps its daylight fill while the text drawn on it follows the theme and turns
    light. The day card did exactly this: white card, near-white numeral, unreadable. Three
    more drawables had it, and the Timeline entry field was a #FFFCFCFD literal that had not
    been noticed yet.

    A drawable that says "tint base" in a comment is exempt, because a shape coloured at
    runtime with setBackgroundTintList in SRC_IN mode has to start white and a night variant
    would break it. Exempting by a phrase in the file keeps the reason next to the code
    rather than in a list here that would rot.
    """
    import os
    print("drawable fills reachable by a theme")
    before = len(fails)
    for p in sorted(glob.glob(f"{RES}/drawable*/*.xml")):
        src = open(p, encoding="utf-8").read()
        if "tint base" in src:
            continue
        for m in re.finditer(r'<solid[^/>]*android:color="([^"]+)"', src):
            c = m.group(1)
            if c.startswith("@color/") or c.startswith("?"):
                continue
            # Transparent has no light or dark form to get wrong.
            if c in ("@android:color/transparent", "#00000000") or (
                    c.startswith("#") and len(c) == 9 and c[1:3] == "00"):
                continue
            note(f"{os.path.basename(p)}: <solid> is {c}, which values-night cannot override. "
                 f"Use a @color/ resource, or document it as a tint base.")
    if len(fails) == before:
        print("  ok")


def check_empty_resources():
    """Zero-byte files under res/.

    An empty PNG compiles fine in debug, because AGP only crunches PNGs for release builds, so
    one can sit in the tree for years and surface as "AAPT: error: file failed to compile" the
    first time anyone runs assembleRelease. AAPT also fails fast per worker, reporting three of
    them at a time, which turns the fix into a dozen rounds of build-and-see. Sixty-five of
    these were inherited from the Markor baseline and found exactly that way.
    """
    import os
    bad = []
    for p in glob.glob(f"{RES}/**/*", recursive=True):
        if not os.path.isfile(p):
            continue
        if os.path.basename(p).startswith("."):
            continue
        if os.path.getsize(p) == 0:
            bad.append(p)
    for p in sorted(bad):
        fail(f"{os.path.relpath(p, RES)}: zero bytes. AAPT rejects this when release crunches it.")


def check_comment_dashes():
    """A double hyphen inside an XML comment is illegal. check_xml already rejects it, but
    as "invalid token" at a column, which says nothing about the cause -- three of these
    have cost a round trip each, so name it before the parser gets there."""
    print("XML comments free of '--'")
    before = len(fails)
    for p in sorted(glob.glob(f"{RES}/**/*.xml", recursive=True)):
        text = io.open(p, encoding="utf-8", errors="replace").read()
        i = 0
        while True:
            a = text.find("<!--", i)
            if a < 0:
                break
            b = text.find("-->", a + 4)
            if b < 0:
                break
            body = text[a + 4:b]
            if "--" in body:
                line = text.count("\n", 0, a + 4 + body.find("--")) + 1
                note(f"{os.path.relpath(p, RES)}:{line}: '--' inside an XML comment. "
                     f"Use a colon, one hyphen, or a new sentence.")
            i = b + 3
    if len(fails) == before:
        print("  ok")


def check_material_widgets():
    """Widgets that demand a MaterialComponents theme, in an app that has not got one.

    MaterialButton and everything built on it -- the extended FAB among them -- call
    ThemeEnforcement.checkMaterialTheme in their constructor and throw
    IllegalArgumentException at inflation if the activity theme does not descend from
    Theme.MaterialComponents. That is a crash on launch, not a warning, and nothing else
    here would catch it: the XML is well formed, the class exists, the attributes are real.

    The plain FloatingActionButton and BottomNavigationView do not enforce, which is why
    they have always worked here, so they are not listed.
    """
    print("MaterialComponents widgets vs the app theme")
    before = len(fails)

    parents = ""
    for p in glob.glob(f"{RES}/values*/styles.xml") + glob.glob(f"{RES}/values*/themes.xml"):
        parents += io.open(p, encoding="utf-8", errors="replace").read()
    has_material_theme = ("MaterialComponents" in parents) or ("Theme.Material3" in parents)

    ENFORCING = [
        "com.google.android.material.button.MaterialButton",
        "com.google.android.material.button.MaterialButtonToggleGroup",
        "com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton",
        "com.google.android.material.chip.Chip",
        "com.google.android.material.textfield.TextInputLayout",
        "com.google.android.material.card.MaterialCardView",
        "com.google.android.material.slider.Slider",
        "com.google.android.material.switchmaterial.SwitchMaterial",
        "com.google.android.material.appbar.MaterialToolbar",
    ]
    if has_material_theme:
        print("  app theme descends from MaterialComponents, nothing to enforce")
        return

    for f in sorted(glob.glob(f"{RES}/layout*/*.xml")):
        text = io.open(f, encoding="utf-8", errors="replace").read()
        for w in ENFORCING:
            if "<" + w in text:
                line = text.count("\n", 0, text.find("<" + w)) + 1
                note(f"{os.path.relpath(f, RES)}:{line}: <{w.rsplit('.', 1)[1]}> needs a "
                     f"Theme.MaterialComponents app theme. This one is AppCompat, so it "
                     f"throws at inflation and crashes the activity.")
    if len(fails) == before:
        print("  ok")


def check_xml():
    print("resource XML well-formed")
    import xml.etree.ElementTree as ET
    before = len(fails)
    for p in sorted(glob.glob(f"{RES}/**/*.xml", recursive=True)):
        try:
            ET.parse(p)
        except Exception as e:
            note(f"{os.path.relpath(p, RES)}: {e}")
    if len(fails) == before:
        print("  ok")


def check_types(java_files):
    print("type resolution")
    owned = {}
    for root in SRC_ROOTS:
        for dp, _, fs in os.walk(root):
            for f in fs:
                if f.endswith(".java"):
                    fq = os.path.relpath(os.path.join(dp, f), root)[:-5].replace(os.sep, ".")
                    owned.setdefault(f[:-5], set()).add(fq)
    before = len(fails)
    for p in java_files:
        src = open(p, encoding="utf-8").read()
        mpkg = re.search(r"^package\s+([\w.]+);", src, re.M)
        if not mpkg:
            continue
        pkg = mpkg.group(1)
        imports = {m.group(1).split(".")[-1]
                   for m in re.finditer(r"^import\s+(?:static\s+)?([\w.]+);", src, re.M)}
        body = strip_code(re.sub(r"^package.*?;|^import.*?;", "", src, flags=re.M))
        siblings = {n for n, q in owned.items() if any(x.rsplit(".", 1)[0] == pkg for x in q)}
        local = set(re.findall(r"\b(?:class|interface|enum|@interface)\s+(\w+)", body))
        seen = set()
        for m in re.finditer(r"(?<![.\w])([A-Z]\w*)\s*(?=[.(<\[]|\s+\w)", body):
            n = m.group(1)
            if re.fullmatch(r"[A-Z0-9_]+", n):
                continue  # constant, not a type
            if n in imports or n in JAVA_LANG or n in siblings or n in local or n in seen:
                continue
            seen.add(n)
            note(f"unresolved type {n} -> {os.path.basename(p)}:"
                 f"{body[:m.start()].count(chr(10)) + 1}")
    if len(fails) == before:
        print("  ok")


def check_fields(java_files, all_java):
    """
    Underscore-prefixed names must be declared somewhere in the project.

    This codebase prefixes fields with "_", which makes the one bug class these checks could
    never otherwise see -- a name that simply does not exist -- cheaply detectable. It is what
    catches a field used in a method when the edit that was supposed to add it anchored on a
    declaration some earlier revert had removed.

    Declarations are collected across every file rather than per file, because a fragment or an
    activity inherits _cu and _appSettings from its base class and those are declared elsewhere.
    That makes the check weaker -- a name that happens to match some other class's field slips
    through -- but it still catches the case that matters, which is a name declared nowhere.
    """
    print("underscore names declared")
    declared = set()
    for p in all_java:
        src = strip_code(open(p, encoding="utf-8").read())
        declared |= set(re.findall(r"[\w>\]]\s+(_\w+)\s*(?:[;=,)]|:)", src))
        # Parameters, including untyped lambda ones. Two fixes over the obvious pattern:
        # *? rather than +? so "(_view, year) ->" counts, and a lookbehind so a dotted name
        # does not. Without the lookbehind "getString(R.string.main_view)" declared "_view"
        # for the whole project, because the lazy part ate "R.string.main" -- which masked a
        # real finding until the file holding that one call was deleted.
        declared |= set(re.findall(r"\(\s*(?:final\s+)?[\w<>\[\].,\s]*?(?<![\w.])(_\w+)\s*[,)]", src))
    before = len(fails)
    for p in java_files:
        src = strip_code(open(p, encoding="utf-8").read())
        used = set(re.findall(r"(?<![\w.])(_\w+)\b", src))
        for n in sorted(used - declared):
            note(f"{n} used but declared nowhere -> {os.path.basename(p)}")
    if len(fails) == before:
        print("  ok")


def check_structure(java_files):
    print("brace balance and stacked @Override")
    before = len(fails)
    for p in java_files:
        s = open(p, encoding="utf-8").read()
        t = strip_code(s)
        if t.count("{") != t.count("}"):
            note(f"{os.path.basename(p)} braces {t.count('{') - t.count('}'):+d}")
        if t.count("(") != t.count(")"):
            note(f"{os.path.basename(p)} parens {t.count('(') - t.count(')'):+d}")
        L = s.split("\n")
        for i in range(len(L) - 1):
            # Two @Override on one method, even with a javadoc wedged between them. It only
            # compared adjacent lines, and the way this actually happens is an edit anchored
            # on a method signature whose @Override is on the line above: the old annotation
            # is left stranded, the new block brings its own, and a comment sits in between.
            # javac calls it "Override is not a repeatable annotation interface".
            if L[i].strip() == "@Override":
                j = i + 1
                while j < len(L):
                    t = L[j].strip()
                    if not t or t.startswith("//") or t.startswith("/*") or t.startswith("*"):
                        j += 1
                        continue
                    break
                if j < len(L) and L[j].strip() == "@Override":
                    note(f"{os.path.basename(p)}:{i + 1} stacked @Override "
                         f"(second at line {j + 1})")
    if len(fails) == before:
        print("  ok")



def check_duplicate_resources():
    """Two <string name="x"> in the same values file is a hard AAPT error.

    Easy to introduce when adding a batch of strings to a long file: the name already exists
    four hundred lines up under somebody else's heading, and nothing here was looking for it.
    Caught in this project after a shipped commit duplicated <string name="rename">.
    """
    print("duplicate resource names")
    before = len(fails)

    # Per values FOLDER, not per file. AAPT merges every file in values/ into one namespace,
    # so <string name="quicknote"> in strings.xml and again in string-not_translatable.xml is
    # a hard error even though neither file repeats itself. The first version of this check
    # counted within each file and missed exactly that, because the bug it was written for --
    # a duplicated <string name="rename"> -- happened to be within one file. Cost a second
    # broken build to notice.
    folders = {}
    for path in sorted(glob.glob(f"{RES}/values*/*.xml")):
        try:
            text = io.open(path, encoding="utf-8").read()
        except Exception as e:
            note(f"{os.path.relpath(path, RES)}: {e}")
            continue
        folder = os.path.basename(os.path.dirname(path))
        for kind in ("string", "plurals", "color", "dimen", "style", "integer", "bool", "array",
                     "string-array"):
            for m in re.finditer(r'<%s\s+name="([^"]+)"' % kind, text):
                line = text.count("\n", 0, m.start()) + 1
                folders.setdefault((folder, kind, m.group(1)), []).append(
                    f"{os.path.relpath(path, RES)}:{line}")

    for (folder, kind, name), where in sorted(folders.items()):
        if len(where) > 1:
            note(f'<{kind} name="{name}"> defined {len(where)} times in {folder}/: '
                 + ", ".join(where))
    if len(fails) == before:
        print("  ok")


def check_undeclared_locals(java_files):
    """A short lowercase name used as `x.something` that is declared nowhere in the file.

    This is what scripted editing orphans. Deleting the line that assigned a result leaves the
    lines that read it, and nothing else here notices: the type resolver only looks at types,
    and the field check only looks at underscore-prefixed fields. Caught after a regex removed
    `JSONObject mg = ` and left `mg.has("ops")` two lines below, which cost a build.

    Deliberately narrow -- names of four characters or fewer -- because those are the ones
    scripted edits strand, and longer names produce false positives faster than they find bugs.
    """
    print("locals used but never declared")
    before = len(fails)
    skip = {"java", "javax", "android", "androidx", "net", "com", "org", "kotlin", "io", "de",
            "this", "super"}
    for path in java_files:
        try:
            code = strip_code(io.open(path, encoding="utf-8").read())
        except Exception:
            continue
        d = set()
        # Declarations and parameters: `Type name` followed by = ; : , )
        d |= set(re.findall(r"\b[A-Z][\w<>\[\],.?\s]*?\s+([a-z]\w{0,3})\s*[=;:,)]", code))
        # Primitive-typed declarations
        d |= set(re.findall(r"\b(?:int|long|char|byte|short|float|double|boolean|var)\s+([a-z]\w{0,3})\s*[=;:,)]", code))
        # Lambda parameters, single or parenthesised lists
        d |= set(re.findall(r"\b([a-z]\w{0,3})\s*->", code))
        for group in re.findall(r"\(([^()]*)\)\s*->", code):
            d |= {t.strip().split()[-1] for t in group.split(",") if t.strip()}
        used = set(re.findall(r"(?<![\w.])([a-z]\w{0,3})\s*\.", code))
        for name in sorted(used - d - skip):
            note(f"{os.path.relpath(path)}: '{name}' used as '{name}.' but never declared")
    if len(fails) == before:
        print("  ok")


def check_project_imports(java_files):
    """An import of a project class whose file does not exist.

    Deleting a class leaves its imports behind, and the type checker here treats an unknown
    capitalised name as something from the SDK or a library, so it says nothing. Caught after
    TodoWidgetProvider was deleted and two files went on importing it -- a compile error that
    reached the user twice over.

    Only our own packages are checked, where an import maps one-to-one onto a file path. There
    is no ambiguity to get wrong and so no false positives.
    """
    print("project imports resolve to files")
    before = len(fails)
    prefixes = ("net.pixelpen", "net.gsantner", "other.writeily")
    src_roots = ["app/src/main/java", "app/thirdparty/java"]
    for path in java_files:
        try:
            text = io.open(path, encoding="utf-8").read()
        except Exception:
            continue
        for imp in re.findall(r"^import\s+(static\s+)?([\w.]+);", text, re.M):
            fq = imp[1]
            if not any(fq.startswith(r + ".") for r in prefixes):
                continue
            parts = fq.split(".")
            # R and BuildConfig are generated at build time and have no source file.
            if parts[-1] in ("R", "BuildConfig") or ".R." in fq:
                continue
            # Walk up every ancestor, not just one: an import may name a nested class or a
            # static member of one, so the file can be several segments short of the import.
            found = False
            for cut in range(len(parts), 1, -1):
                rel = "/".join(parts[:cut]) + ".java"
                if any(os.path.exists(os.path.join(r, rel)) for r in src_roots):
                    found = True
                    break
            if not found:
                note(f"{os.path.basename(path)}: imports {fq}, no such file")
    if len(fails) == before:
        print("  ok")


def main():
    targets = sys.argv[1:]
    all_java = [p for r in SRC_ROOTS for p in glob.glob(f"{r}/**/*.java", recursive=True)]
    focus = targets or all_java
    check_structure(focus)
    check_fields(focus, all_java)
    check_types(all_java)
    check_undeclared_locals(all_java)
    check_resources(all_java)
    check_project_imports(all_java)
    check_xml()
    check_material_widgets()
    check_comment_dashes()
    check_duplicate_resources()
    check_empty_resources()
    check_unthemed_drawable_fills()
    check_daynight_theme()
    check_untinted_black_icons()
    print()
    if fails:
        print(f"{len(fails)} problem(s) -- do not send")
        return 1
    print(f"clean: {len(all_java)} java files, {len(glob.glob(f'{RES}/**/*.xml', recursive=True))} resource files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
