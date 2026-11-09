#!/usr/bin/env python3
"""
Run the Tree expansion tests.

    python3 tools/treetest/run.py

Why this exists
---------------
The Tree's collapse/expand logic broke in a way no static check could see and no
build could fail on: seedExpansion recorded the path of a category folder that
did not exist on disk, so collect() could never reach it. The request was made,
the tree redrew, and nothing opened. From the outside that is indistinguishable
from the expansion logic being broken, which is how it survived several rounds of
looking at the wrong layer.

What makes it testable is that the logic is pure path arithmetic over
java.io.File -- no Android types at all. So rather than reimplement it here
(a reimplementation proves nothing about the shipped code), this script lifts
the method bodies VERBATIM out of FolderTreeFragment.java on every run, wraps
them in scaffolding, and executes them against a real temporary archive shaped
like an imported one.

Lifting on every run means the test cannot drift from the code. If a method is
renamed or reshaped, extraction fails loudly rather than testing a stale copy.

Needs a JDK on PATH. Nothing else -- deliberately not wired into Gradle, so it
runs in a couple of seconds without an Android SDK.
"""

import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(
    REPO, "app/src/main/java/net/pixelpen/journal/activity/FolderTreeFragment.java"
)

# Signatures lifted verbatim. Order matters only for readability of the generated file.
SIGNATURES = [
    "private void seedExpansion(final File root)",
    "private File targetMonthDir(final File root)",
    "private static boolean isUnder(final File root, final File f)",
    "private static int relDepth(final File root, final File f)",
    "public void collapseCategories()",
]


def extract(source, signature):
    """Return one method's text, from its signature to its matching closing brace."""
    start = source.find(signature)
    if start < 0:
        raise LookupError(signature)
    depth = 0
    i = source.index("{", start)
    while True:
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start : i + 1]
        i += 1


def main():
    if not os.path.isfile(SRC):
        sys.exit("Source not found: " + SRC)
    source = open(SRC, encoding="utf-8").read()

    try:
        bodies = [extract(source, s) for s in SIGNATURES]
    except LookupError as missing:
        sys.exit(
            "Could not find this method in FolderTreeFragment.java:\n    %s\n\n"
            "It was renamed or reshaped. Update SIGNATURES in this script so the test "
            "keeps exercising the real code instead of silently testing nothing." % missing
        )

    head = open(os.path.join(HERE, "template_head.java"), encoding="utf-8").read()
    tail = open(os.path.join(HERE, "template_tail.java"), encoding="utf-8").read()

    work = tempfile.mkdtemp(prefix="treetest-")
    try:
        java = os.path.join(work, "TreeExpansionTest.java")
        with open(java, "w", encoding="utf-8") as fh:
            fh.write(head)
            fh.write(
                "    public void expandCategoryOnNextDraw(final File dir) {\n"
                "        _pendingCategoryExpand = "
                "(dir == null) ? null : dir.getAbsolutePath();\n"
                "    }\n\n"
            )
            fh.write("\n\n".join(bodies))
            fh.write("\n\n")
            fh.write(tail)

        compiled = subprocess.run(
            ["javac", "-nowarn", "-d", work, java],
            capture_output=True,
            text=True,
        )
        if compiled.returncode != 0:
            print(compiled.stdout + compiled.stderr)
            sys.exit("Could not compile the lifted methods.")

        archive = os.path.join(work, "archive", "Autumn")
        run = subprocess.run(
            ["java", "-cp", work, "TreeExpansionTest", archive],
            capture_output=True,
            text=True,
        )
        out = "\n".join(
            line for line in (run.stdout + run.stderr).splitlines()
            if not line.startswith("Picked up ")
        )
        print(out)
        # The harness prints its own verdict; trust the text, not the exit code, because
        # System.exit inside the harness has been unreliable to read through the wrapper.
        sys.exit(0 if "ALL PASS" in out else 1)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
