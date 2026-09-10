#!/usr/bin/env bash
# On Windows git checks out symlinks as plain text files containing the link
# target ("core.symlinks=false"), which breaks the native build of
# hev-socks5-tunnel: the compiler sees empty headers instead of C sources.
#
# This script replaces those stub files with the real file contents.
# Run it once after cloning on Windows (Git Bash / MSYS2), then build.
#
# On Linux and macOS nothing needs to be done: symlinks are checked out
# properly there, and this script detects that and exits immediately.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEV="$ROOT/app/src/main/jni/hev-socks5-tunnel"

fixed=0
skipped=0

fix_repo() {
    local repo="$1"
    [ -d "$repo" ] || { echo "missing repo: $repo" >&2; return 0; }
    [ -e "$repo/.git" ] || { echo "not a git repo: $repo" >&2; return 0; }

    while IFS= read -r path; do
        # already a real symlink (Linux/macOS): nothing to do
        if [ -L "$repo/$path" ]; then
            skipped=$((skipped + 1))
            continue
        fi

        local cur="$path" mode target
        # follow chains of symlink stubs to the final real file
        for _ in 1 2 3 4 5; do
            mode="$(git -C "$repo" ls-files -s -- "$cur" | awk '{print $1}')"
            [ "$mode" = "120000" ] || break
            target="$(tr -d '\r\n' < "$repo/$cur")"
            cur="$(dirname "$cur")/$target"
        done

        if [ -f "$repo/$cur" ]; then
            cp -f "$repo/$cur" "$repo/$path"
            fixed=$((fixed + 1))
        else
            echo "unresolved: $path -> $cur" >&2
        fi
    done < <(git -C "$repo" ls-files -s | awk '$1==120000 {print $4}')
}

fix_repo "$HEV"
fix_repo "$HEV/src/core"
fix_repo "$HEV/third-part/yaml"
fix_repo "$HEV/third-part/hev-task-system"

if [ "$fixed" -eq 0 ] && [ "$skipped" -gt 0 ]; then
    echo "nothing to fix: symlinks are real ($skipped paths)"
else
    echo "done (fixed: $fixed, already real: $skipped)"
fi
