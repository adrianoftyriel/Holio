#!/bin/bash
# SessionStart hook: install the rtk token-optimizing CLI so the committed
# PreToolUse hook (.claude/hooks/rtk-hook.sh) can transparently rewrite common
# dev commands (git status, etc.) into their trimmed `rtk ...` equivalents.
#
# Best-effort by design: rtk is an optimization, not a hard dependency, so this
# script always exits 0 and never blocks session startup on a failure.
set -uo pipefail

# Pin the version. Remote environments often block api.github.com, so the
# installer's unauthenticated "latest" lookup can't run — pinning avoids it.
# Bump this to upgrade rtk across future sessions.
RTK_VERSION="v0.43.0"

# Only auto-install in Claude Code on the web; leave local machines untouched.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

BIN="$HOME/.local/bin/rtk"

# Idempotent: skip the download if the pinned version is already installed
# (container state is cached between sessions, so this is usually a no-op).
if [ -x "$BIN" ] && "$BIN" --version 2>/dev/null | grep -q "${RTK_VERSION#v}"; then
  echo "[rtk] already installed: $("$BIN" --version 2>/dev/null)"
else
  echo "[rtk] installing $RTK_VERSION ..."
  if ! curl -fsSL https://raw.githubusercontent.com/rtk-ai/rtk/refs/heads/master/install.sh \
      | RTK_VERSION="$RTK_VERSION" sh; then
    echo "[rtk] install failed — continuing without token optimization" >&2
    exit 0
  fi
fi

# Make rtk resolvable to the hook regardless of the shell's PATH/profile.
ln -sf "$BIN" /usr/local/bin/rtk 2>/dev/null || true

# Persist PATH for this session's shells.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$CLAUDE_ENV_FILE"
fi

exit 0
