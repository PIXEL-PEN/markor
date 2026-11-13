#!/bin/bash
# Run the checks on HEAD and on the pre-fork base, and report only what is NEW.
#
# The checks are noisy on upstream code: a subclass using its parent's nested type
# unqualified reads as an unresolved type here, and there are a couple of library
# resources the app references but does not declare. None of that is actionable,
# and all of it predates this work. Comparing against the base commit is what
# separates "my problem" from "inherited".
set -e
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${REPO:-$(cd "$HERE/../.." && pwd)}"
# The base is a COMMIT, not a branch name: the point autumn forked from the Markor line,
# which is an ancestor of autumn and so can never be garbage-collected. Pinned by hash
# because the markor-plus-v1.2.6 branch it used to be named by has been deleted.
BASE="${1:-c4cd8a82}"
cd "$REPO"

# A crash used to pass. check.py exits 1 whenever it has findings, so the exit code cannot
# tell a clean run from a broken one; the output was piped straight into grep, so a check
# that threw partway through contributed its findings up to the throw and the comparison
# still printed NO NEW PROBLEMS. A new check with a typo in it therefore looked like a check
# that found nothing. The run is captured and inspected for a traceback first now.
python3 -I "$HERE/check.py" > /tmp/now.raw 2>&1 || true
if grep -q "Traceback (most recent call last)" /tmp/now.raw; then
  echo "CHECKS CRASHED -- the result below would be meaningless, so there is none."
  echo
  grep -A 12 "Traceback (most recent call last)" /tmp/now.raw | tail -8
  exit 1
fi
grep "^  FAIL" /tmp/now.raw | sed 's/:[0-9]*$//' | sed 's#FAIL [^ ]*/#FAIL #' | sort > /tmp/now.txt
WT=$(mktemp -d)
git worktree add -q --detach "$WT" "$BASE"
( cd "$WT" && python3 -I "$HERE/check.py" 2>&1 | grep "^  FAIL" | sed 's/:[0-9]*$//' | sed 's#FAIL [^ ]*/#FAIL #' | sort > /tmp/base.txt ) || true
git worktree remove --force "$WT"
echo "base($BASE): $(wc -l < /tmp/base.txt) pre-existing   HEAD: $(wc -l < /tmp/now.txt)"
echo
NEW=$(comm -13 /tmp/base.txt /tmp/now.txt)
if [ -z "$NEW" ]; then
  echo "NO NEW PROBLEMS"
else
  echo "NEW (introduced by this work):"; echo "$NEW"
fi
FIXED=$(comm -23 /tmp/base.txt /tmp/now.txt | wc -l)
echo; echo "($FIXED pre-existing findings no longer present, mostly in deleted files)"

echo
python3 -I "$HERE/check-removed-methods.py" || exit 1
