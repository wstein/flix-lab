# vendor/rewrite

`src/flix/JavaRewriteDemo.flix` depends on `org.openrewrite:*:0.1.0-SNAPSHOT`,
built from a private fork and not published to Maven Central, so it can't be
resolved via Flix's `[mvn-dependencies]`. `flix.toml`'s `[jar-dependencies]`
instead points at 7 jars expected in `rewrite/` via absolute `file://` URLs,
since that's the only mechanism Flix has for a jar that isn't fetchable by
URL from a public host or resolvable from Central.

**These jars are not committed to git** (`rewrite/*.jar` stays covered by the
repo's `*.jar` gitignore rule) -- run this once after cloning, before
`flix check`/`flix run --entrypoint rewriteDemo`:

```console
./vendor/setup-rewrite.sh
```

It downloads the source tarball below and extracts the 7
`org/openrewrite/*/0.1.0-SNAPSHOT/*.jar` files (excluding `-sources`/
`-javadoc`) into `rewrite/`.

Source: <https://github.com/wstein/java2kotlin-vendor-artifacts/releases/download/vendor-2026.07.20.1/rewrite-m2-vendor-2026.07.20.1.tar.gz>

Caveat: because `file://` URLs must be absolute, `flix.toml` hardcodes the
path to this directory as it exists on this checkout
(`/Users/werner/github.com/wstein/flix-lab/vendor/rewrite`). Cloning this
repo elsewhere requires updating those paths in `flix.toml` to match the new
checkout location, in addition to running the setup script above.
