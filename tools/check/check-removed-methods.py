#!/usr/bin/env python3
"""Methods deleted from a settings-style singleton while callers remain.

Scoped deliberately. The general question -- "was any removed method left with callers?" --
cannot be answered by name alone: getAction() belongs to Intent and MotionEvent as well as to
a deleted project class, so matching names flags the SDK and a check that cries wolf is one I
will stop reading. Answering it properly needs type resolution, which is a compiler's job, and
there is no android.jar here to run one against.

So this watches the files that scripted edits in this project actually damage -- AppSettings
and friends -- and pins the receiver, which removes the ambiguity entirely. Three build breaks
here were exactly this shape: a cut that took a neighbouring method with it, found by javac on
the user's machine rather than before sending.
"""
import re, subprocess, sys, os, glob, io

import os
REPO = os.environ.get("REPO") or os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = sys.argv[1] if len(sys.argv) > 1 else "c4cd8a82"

# class file -> the identifiers an instance of it is held in across the codebase
WATCHED = {
    "app/src/main/java/net/pixelpen/journal/model/AppSettings.java":
        # Receivers include calls, not just variables: as().isDefaultPasswordSet() slipped
        # through when only the bare name "as" was listed, and cost a build.
        ["_appSettings", "appSettings", "as", "as()", "settings", "settings()",
         "ApplicationObject.settings()", "getAppSettings()"],
    "app/src/main/java/net/pixelpen/journal/model/ArchiveImages.java": ["ArchiveImages"],
    "app/src/main/java/net/pixelpen/journal/model/YearOverview.java": ["overview", "_year"],
    "app/src/main/java/net/pixelpen/journal/model/Timeline.java": ["Timeline", "timeline"],
    "app/src/main/java/net/pixelpen/journal/util/CategoryManager.java": ["CategoryManager"],
}

DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?"
    r"(?:@\w+\s+)?[\w.<>,\[\]?]+\s+([a-zA-Z_]\w*)\s*\(", re.M)

# build.gradle adds thirdparty/java as a source root. Leaving it out meant the launcher
# widget kept calling two deleted methods with nothing to say so.
now_text = {}
for root in ("app/src/main/java", "app/thirdparty/java"):
    for p in glob.glob(os.path.join(REPO, root, "**/*.java"), recursive=True):
        now_text[p] = io.open(p, encoding="utf-8", errors="ignore").read()

problems = []
examined = 0
for rel, receivers in WATCHED.items():
    # The project renamed its package, so a path that exists now did not exist at the tag.
    # Try today's path, then the upstream one it was renamed from.
    base_blob = None
    for candidate in (rel, rel.replace("net/pixelpen/journal", "net/gsantner/markor")):
        r = subprocess.run(["git", "-C", REPO, "show", f"{BASE}:{candidate}"],
                           capture_output=True, text=True)
        if r.returncode == 0:
            base_blob = r
            break
    if base_blob is None:
        continue                      # file did not exist at base; nothing to lose
    path = os.path.join(REPO, rel)
    if not os.path.exists(path):
        continue
    gone = DECL.findall(base_blob.stdout)
    still = set(DECL.findall(io.open(path, encoding="utf-8").read()))
    for name in sorted(set(gone) - still):
        examined += 1
        hits = []
        for p, t in now_text.items():
            same_file = os.path.abspath(p) == os.path.abspath(path)
            for i, line in enumerate(t.split("\n")):
                # Inside the defining class the receiver is implicit, so look for a bare call.
                # Skipping this file entirely -- as this check first did -- missed a removed
                # method still called by its own class, which is how setEditorBasicColor
                # survived in init().
                if same_file:
                    import re as _re
                    if _re.search(r"(?<![\w.])" + _re.escape(name) + r"\s*\(", line) \
                            and "public" not in line and "private" not in line \
                            and "protected" not in line:
                        hits.append((p, i + 1, line.strip()))
                    continue
                s = line.strip()
                if s.startswith("//") or s.startswith("*"):
                    continue
                if any((r + "." + name + "(") in line for r in receivers):
                    hits.append((p, i + 1, s))
        if hits:
            problems.append((rel, name, hits))

if problems:
    print("settings methods removed while callers remain:")
    for rel, name, hits in problems:
        print("  FAIL %s.%s() -- %d call site(s)" % (os.path.basename(rel)[:-5], name, len(hits)))
        for p, ln, text in hits[:3]:
            print("        %s:%d  %s" % (os.path.relpath(p, REPO), ln, text[:88]))
    sys.exit(1)
print("removed-method check: ok (%d removed methods examined, none still called)" % examined)
