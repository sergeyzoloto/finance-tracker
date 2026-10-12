#!/usr/bin/env bash
# D-143: fail if a tracked file, the index (what a commit would hold) or a commit names
# a person listed in data/private/forbidden-names.txt (git-ignored, one name per line,
# blank lines and lines starting with # ignored). Matching is a case-insensitive,
# Unicode-aware substring search (NFC, then str.casefold). Hits are reported as
# "path:line" only, never the text that matched. A local check, not run in CI.
#
#   scripts/check-forbidden-names.sh              the working tree's tracked files and the index
#   scripts/check-forbidden-names.sh --rev REV    the files of commit REV (e.g. HEAD)
#   scripts/check-forbidden-names.sh --log RANGE  the messages of the commits in RANGE
#
# Exit status: 0 no hit, 1 a hit, 2 usage or a missing names file.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
names="${FORBIDDEN_NAMES_FILE:-$root/data/private/forbidden-names.txt}"

if [ ! -f "$names" ]; then
  echo "check-forbidden-names: $names is missing." >&2
  echo "Create it with one name per line (every spelling: Cyrillic, Latin, short forms):" >&2
  echo "  install -m 600 /dev/null data/private/forbidden-names.txt" >&2
  echo "then edit it. data/private/ is git-ignored; never commit the file." >&2
  exit 2
fi
if git -C "$root" ls-files --error-unmatch -- "$names" >/dev/null 2>&1; then
  echo "check-forbidden-names: the names file is tracked by git; remove it from the index first." >&2
  exit 2
fi

mode="${1:-tree}"
arg="${2:-}"
case "$mode" in
  tree) ;;
  --rev|--log) [ -n "$arg" ] || { echo "usage: $0 [--rev REV | --log RANGE]" >&2; exit 2; } ;;
  *) echo "usage: $0 [--rev REV | --log RANGE]" >&2; exit 2 ;;
esac

cd "$root"
python3 -I - "$names" "$mode" "$arg" <<'PY'
import subprocess, sys, unicodedata

names_file, mode, arg = sys.argv[1], sys.argv[2], sys.argv[3]


def fold(s):
    return unicodedata.normalize('NFC', s).casefold()


with open(names_file, encoding='utf-8') as fh:
    names = [fold(l.strip()) for l in fh if l.strip() and not l.lstrip().startswith('#')]
if not names:
    print('check-forbidden-names: the names file lists no name.', file=sys.stderr)
    sys.exit(2)


def git(*a):
    return subprocess.run(['git', *a], check=True, capture_output=True).stdout


def blob(oid):
    return git('cat-file', 'blob', oid)


hits = []


def scan(label, data):
    text = fold(data.decode('utf-8', errors='replace'))
    for n, line in enumerate(text.split('\n'), 1):
        if any(name in line for name in names):
            hits.append(f'{label}:{n}')


def scan_path(label, path, where):
    if any(name in fold(path) for name in names):
        hits.append(f'{label}: a path in {where} (entry {where_index[where]})')


where_index = {}


def entries(listing):
    for i, rec in enumerate(listing.split(b'\0'), 1):
        if rec:
            yield i, rec


if mode == 'tree':
    # The working tree's tracked files.
    for i, rec in entries(git('ls-files', '-z')):
        path = rec.decode('utf-8', errors='replace')
        where_index['git ls-files'] = i
        scan_path('worktree', path, 'git ls-files')
        try:
            with open(rec, 'rb') as fh:
                scan(f'worktree {path}', fh.read())
        except (FileNotFoundError, IsADirectoryError):
            pass  # deleted in the working tree, or a submodule
    # The index: what the next commit would hold, the staged diff included.
    for i, rec in entries(git('ls-files', '-s', '-z')):
        meta, path = rec.split(b'\t', 1)
        oid = meta.split()[1].decode()
        where_index['git ls-files -s'] = i
        scan_path('index', path.decode('utf-8', errors='replace'), 'git ls-files -s')
        if meta.split()[0] != b'160000':
            scan(f'index {path.decode("utf-8", errors="replace")}', blob(oid))
elif mode == '--rev':
    for i, rec in entries(git('ls-tree', '-r', '-z', arg)):
        meta, path = rec.split(b'\t', 1)
        kind, oid = meta.split()[1].decode(), meta.split()[2].decode()
        where_index['git ls-tree'] = i
        scan_path(arg, path.decode('utf-8', errors='replace'), 'git ls-tree')
        if kind == 'blob':
            scan(f'{arg} {path.decode("utf-8", errors="replace")}', blob(oid))
else:
    for oid in git('rev-list', arg).decode().split():
        scan(f'commit message {oid}', git('log', '-1', '--format=%B', oid))

if hits:
    print(f'check-forbidden-names: {len(hits)} hit(s); the matched text is not shown.')
    for h in hits:
        print('  ' + h)
    sys.exit(1)
print(f'check-forbidden-names: no hit ({mode}{" " + arg if arg else ""}).')
PY
