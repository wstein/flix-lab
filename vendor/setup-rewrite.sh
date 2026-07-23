#!/bin/bash
# Downloads the org.openrewrite:*:0.1.0-SNAPSHOT fork jars used by
# src/JavaRewriteDemo.flix into vendor/rewrite/. These are not committed to
# git (see vendor/README.md) and not published to Maven Central, so this
# script must be run once after cloning before `flix check`/`flix run
# --entrypoint rewriteDemo` will work.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$SCRIPT_DIR/rewrite"
URL="https://github.com/wstein/java2kotlin-vendor-artifacts/releases/download/vendor-2026.07.20.1/rewrite-m2-vendor-2026.07.20.1.tar.gz"

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading $URL"
curl -fsSL -o "$TMP/rewrite-vendor.tar.gz" "$URL"

echo "Extracting jars into $DEST"
tar -xzf "$TMP/rewrite-vendor.tar.gz" -C "$TMP"
find "$TMP/m2-repository/org/openrewrite" -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" \
    -exec cp {} "$DEST/" \;

echo "Done. Jars installed in $DEST:"
ls -la "$DEST"
