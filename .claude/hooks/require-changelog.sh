#!/usr/bin/env bash
# Stop hook: do not let Claude finish while files changed but change_log.mdx did not.
input=$(cat)

# Anti-loop: if Claude is already continuing because of this hook, let it stop.
if printf '%s' "$input" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

changes=$(git status --porcelain --untracked-files=all 2>/dev/null)
[ -z "$changes" ] && exit 0

if printf '%s\n' "$changes" | grep -q 'change_log\.mdx$'; then
  exit 0
fi

echo "Files in the working tree changed, but change_log.mdx did not. Add an entry to change_log.mdx in its existing format (see CLAUDE.md, Definition of done). If these changes were not made in this session, tell the user instead." >&2
exit 2
