#!/usr/bin/env bash
# On Windows git checks out symlinks as plain text files containing the link
# target ("core.symlinks=false"), which breaks the native build of
# hev-socks5-tunnel: the compiler sees empty headers.
#
# This script replaces those stub files with the real file contents.
# Run it once after cloning on Windows (WSL/MSYS/Git Bash), then build.
#
# On Linux/macOS nothing needs to be done: symlinks are checked out properly.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEV="$ROOT/app/src/main/jni/hev-socks5-tunnel"

fix_repo() {
    repo="$1"
    [ -d "$repo" ] || { echo "missing repo: $repo" >&2; return 0; }
    [ -d "$repo/.git" ] || [ -f "$repo/.git" ] || { echo "not a git repo: $repo" >&2; return 0; }

    git -C "$repo" ls-files -s | awk '$1==120000 {print $4}' | while IFS= read -r path; do
        cur="$path"
        # follow chains of symlink stubs to the final real file
        for _ in 1 2 3 4 5; do
            mode="$(git -C "$repo" ls-files -s -- "$cur" | awk '{print $1}')"
            [ "$mode" = "120000" ] || break
            target="$(tr -d '\r\n' < "$repo/$cur")"
            cur="$(dirname "$cur")/$target"
        done

        if [ -f "$repo/$cur" ]; then
            cp -f "$repo/$cur" "$repo/$path"
            echo "fixed: ${path}"
        else
            echo "unresolved: $path -> $cur" >&2
        fi
    done
}

fix_repo "$HEV"
fix_repo "$HEV/src/core"
fix_repo "$HEV/third-part/yaml"
fix_repo "$HEV/third-part/hev-task-system"

echo "done"
