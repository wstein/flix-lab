#!/bin/bash
# Packages this directory as a .vsix and (re)installs it into VS Code.
#
# VS Code no longer loads a bare symlinked folder dropped into
# ~/.vscode/extensions -- it only loads extensions that also have a matching
# entry in extensions.json, which `code --install-extension` writes for you.
# Re-run this after editing package.json or FlixDebugAdapter.java, then
# reload the window (Developer: Reload Window) to pick up the change.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VSIX="$(mktemp -d)/flix-debug.vsix"

npx --yes @vscode/vsce package \
    --allow-missing-repository \
    --skip-license \
    --baseContentUrl "https://example.invalid/" \
    --baseImagesUrl "https://example.invalid/" \
    -o "$VSIX" \
    --cwd "$DIR"

code --install-extension "$VSIX"

echo "Installed. Reload VS Code (Developer: Reload Window) to pick it up."
