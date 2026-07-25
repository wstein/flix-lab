# flix-jetbrains-plugin (scaffold, superseded)

> [!NOTE]
> This single-module prototype has been ported to its own standalone repository,
> [wstein/flix-jetbrains-plugin](https://github.com/wstein/flix-jetbrains-plugin), using the
> IntelliJ Platform's newer generator (frontend/backend/shared content-module split, Split Mode
> enabled). All further development happens there. This directory is kept as-is for historical
> reference -- everything it documents (the two real LSP4IJ dispatch bugs found and fixed, the
> `evaluate`/record-pretty-printing work in the shared `FlixDebugAdapter.java`, the TextMate
> bundle-registration mechanics) was carried over to the new repo, but this copy of the plugin
> itself is no longer maintained or built.

An IntelliJ Platform plugin giving Flix the same two things the VS Code side of this project has:
language features (via LSP) and `--Xdebug` JDWP breakpoint debugging (via DAP). It doesn't
reimplement either -- it wires [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) up to
the exact same `flix lsp` language server the official VS Code extension downloads, and to this
project's own [`debug-adapter/src/FlixDebugAdapter.java`](../debug-adapter/src/FlixDebugAdapter.java)
(embedded as a plugin resource, single source of truth, not forked).

## Why LSP4IJ

IntelliJ's own native LSP Client API (opened up to all users in the 2025.3 unified distribution,
open-sourced further in 2026.2) covers language features, but has no DAP equivalent. LSP4IJ's
generic DAP client -- verified by reading its actual source, not just its docs -- is the only
generic path into IntelliJ's debugger for a custom, non-JVM-native protocol like DAP today. See the
debate earlier in this project's history for the fuller reasoning and the alternatives considered.

## What's actually verified here, and what isn't

This was built by reading LSP4IJ's real source (extension-point XML, `DebugAdapterDescriptor`,
`ServerReadyConfig`, `AttachConfigurable`, etc. -- not just its docs, which turned out to contain at
least one nonexistent method in its own example code) and by running real, local checks:

**Verified end-to-end in real `./gradlew runIde` sessions (2026-07-25):**

- **Language features**: `FlixLanguageServer` starts via LSP4IJ and attaches to `.flix` files --
  confirmed by LSP4IJ itself reporting "Flix Language Server" as the active server for `Main.flix`,
  and by real syntax highlighting + diagnostics/completion showing up when editing it.
- **Debugging, fully**: attach to a `--Xdebug`-suspended target, breakpoint set in `Main.flix`,
  execution paused at it. Getting here required finding and fixing two real bugs (not just
  wiring/config mistakes) in the initial scaffold, both confirmed by reading LSP4IJ's actual source
  after live symptoms didn't match the design assumptions:
  1. **`launch` vs `attach` DAP command dispatch.** `DAPClient` picks the literal DAP command it
     sends (`launch` vs `attach`) purely from `getDebugMode()`, ignoring the `"request"` field
     inside `getDapParameters()` entirely. `FlixDebugAdapterDescriptor.getDebugMode()` correctly
     returns `LAUNCH` (LSP4IJ must spawn our adapter process -- it isn't embedded in the debuggee),
     but `FlixDebugAdapter.java`'s dispatcher had no `case "launch"`, so it silently no-op'd every
     session: connected, then did nothing -- `vm` never got set, the target never resumed, no
     breakpoint could ever resolve. Fixed by treating `launch` and `attach` identically in the
     adapter (both just mean "attach to the JDWP host:port in `arguments`") and setting
     `"request": "launch"` in `getDapParameters()` to match what's actually sent.
  2. **`isDebuggableFile()` never consulted the Mappings tab.** The inherited default only checks
     the plugin-level `serverDefinition` (from plugin.xml, which has no file mapping of its own --
     there's no DAP equivalent of the LSP `fileNamePatternMapping` extension point). The Mappings
     tab's `*.flix` entry is stored on `DAPRunConfigurationOptions` instead, and nothing reads it
     unless a descriptor explicitly checks `options instanceof DAPRunConfigurationOptions`, which
     `DefaultDebugAdapterDescriptor` (LSP4IJ's own generic descriptor for user-defined servers) does
     but ours didn't. Every breakpoint was logged as "Breakpoint not supported" regardless of what
     the Mappings tab showed. Fixed by overriding `isDebuggableFile()` to delegate to
     `DAPRunConfigurationOptions.isDebuggableFile()`.
- Operational note, not a bug: each `--Xdebug` target process is consumable exactly once per
  session -- once attach+resume succeeds and the program runs to completion, that JVM exits and a
  "Restart" needs a fresh suspended target on the JDWP port (identical to the VS Code workflow).

**Still not verified:** exact variable/value rendering through LSP4IJ's DAP variable tree (whether
`FlixDebugAdapter`'s existing field-expansion output for `Tag$Obj$Obj`/`RecordExtend$Obj` renders
usefully or needs DAP-side presentation hints); the `flix.runMain` CodeLens gap noted below remains
open.

**Compile/package-time verification** (still applies): `./gradlew compileJava` and
`verifyPluginProjectConfiguration buildPlugin` both pass against the real LSP4IJ 0.20.1 API, not
just its docs -- which, across this whole exercise, contained multiple examples that don't actually
compile or work against the real released API (see the two bugs above, plus the
`getDebugAdapterServerPath` non-existent method noted further down). Every fix in this file was
verified against LSP4IJ's real source or a live session, not assumed from documentation.

## Build

```console
cd jetbrains-plugin
./gradlew buildPlugin
```

Produces `build/distributions/flix-jetbrains-plugin-0.1.0.zip`.

## Install (sideload, matching the VS Code extension's posture)

No JetBrains Marketplace listing (consistent with this project's VS Code extension being
`"private": true` / sideloaded via `debug-adapter/install.sh` -- see that decision's rationale in
the parent project's session history). Install the built zip manually:

Settings/Preferences -> Plugins -> gear icon -> Install Plugin from Disk... -> select the zip.

You'll also need [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) installed from the
Marketplace (it's a `<depends>`, IntelliJ should prompt for it).

## One-time setup per project

1. **Language features**: open a `.flix` file; LSP4IJ should offer to start the "Flix Language
   Server" automatically (via `fileNamePatternMapping`). Requires a `flix-vendor-*.jar` in the
   project root (or `$FLIX_FORK_JAR` set), same as `scripts/flix-fork`.
2. **Syntax highlighting**: nothing to set up -- comes from the language server's LSP semantic
   tokens automatically (the same mechanism the official VS Code extension uses; its `package.json`
   declares `"grammars": []`, there's no static TextMate grammar to import, an earlier version of
   this doc incorrectly said otherwise). The catch: semantic tokens only render once the server has
   actually analyzed the file, so a file that's slow to process (or hits one of LSP4IJ's own
   `ReadAction` timeout warnings, seen live on a large Java-interop-heavy file) shows no highlighting
   at all in the meantime. A bundled static grammar (`textmate/flix.tmLanguage.json`, derived from
   `flix-fork`'s lexer/parser) is included as a baseline fallback that doesn't depend on the language
   server -- see the "TextMate grammar" section below for how it was generated and its limitations.
3. **Debugging**: Run -> Edit Configurations -> `+` -> Debug Adapter Protocol -> Server tab, select
   "Flix (--Xdebug attach)" -> **Mappings tab, add `*.flix`** (required; LSP4IJ has no
   plugin.xml-level equivalent of the LSP mapping for DAP servers) -> Configuration tab, set Debug
   mode to Attach with the JDWP host/port your `--Xdebug` process is listening on (defaults to
   `localhost:5005`, matching this project's existing `.vscode/launch.json` convention).

## Known gaps vs. the "best-in-class" bar

Carried over from the earlier debate's consensus -- this targets VS Code parity through reuse, not
IntelliJ-native polish:

- No PSI, so no structural refactors/inspections beyond what LSP4IJ derives generically from LSP.
- Debugger variable rendering is whatever LSP4IJ's generic DAP variable tree does with
  `FlixDebugAdapter`'s existing field-expansion output (`Tag$Obj$Obj`, `RecordExtend$Obj`
  cons-cells, etc.) -- no IntelliJ-side ADT-aware pretty-printing yet.
- `FlixDebugAdapter`'s `evaluate` DAP request is unimplemented (falls through to a no-op), which
  LSP4IJ's DAP guide calls out as an expected capability for expression evaluation/watches.
