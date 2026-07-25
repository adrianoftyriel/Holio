#!/bin/bash
# PreToolUse(Bash) hook: delegate to rtk's command rewriter when rtk is
# available, otherwise pass the command through unchanged.
#
# rtk reads the hook payload from stdin and emits JSON that rewrites the command
# (e.g. `git status` -> `rtk git status`). If rtk isn't installed — for example
# a local checkout where the SessionStart installer didn't run — we emit nothing
# and exit 0, so the original command runs untouched and Bash is never blocked.
if command -v rtk >/dev/null 2>&1; then
  exec rtk hook claude
fi
exit 0
