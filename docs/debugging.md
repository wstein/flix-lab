# Debugging Flix programs

A practical guide to setting real breakpoints in `.flix` source and stepping
through a running program, in either VS Code or IntelliJ-based IDEs. For how
the underlying adapter and IDE integrations actually work, see
[debug-adapter/README.md](../debug-adapter/README.md) (VS Code extension,
`FlixDebugAdapter.java` internals) and
[jetbrains-plugin's README](https://github.com/wstein/flix-jetbrains-plugin#readme)
(IntelliJ plugin, LSP4IJ wiring).

## Prerequisites

Upstream Flix has no debug info at all. Everything here depends on
[wstein/flix-fork](https://github.com/wstein/flix-fork)'s `--Xdebug` flag,
which emits a `LocalVariableTable`, real per-statement line numbers, and a
JSR-45 `SourceDebugExtension` (SMAP) declaring a `"Flix"` stratum, so the
JVM's own debug machinery (`com.sun.jdi`) can resolve breakpoints and locals
against real `.flix` files and line numbers. Build that fork and drop the
resulting jar in this project's root (`flix-vendor-*.jar`, gitignored) --
[scripts/flix-fork](../scripts/flix-fork) always runs the most recently
modified one there (or `$FLIX_FORK_JAR`, if set).

## Two ways to start a debug session

- **Attach**: start the target program yourself, suspended, with a JDWP
  agent listening; the debugger then connects to it. Always works, and is
  the only option when you need a non-default `--entrypoint` or a
  `--Xdebug`-capable command other than plain `flix` (like this project's
  own `scripts/flix-fork` -- see below).
- **Launch**: the debugger spawns `flix run --Xdebug` itself, on a
  JDWP port it picks, and attaches automatically. No separate step to start
  the target first, but the command it runs (default: `flix` on `PATH`,
  entry point: default `main()`) can't be overridden per-session in every
  client.

## VS Code

**Attach**, via a background task + `launch.json` config (see
[.vscode/tasks.json](../.vscode/tasks.json) /
[.vscode/launch.json](../.vscode/launch.json) for the exact ones this repo
ships):

```json
{
    "type": "flix",
    "name": "Flix: attach demo",
    "request": "attach",
    "hostName": "localhost",
    "port": 5005,
    "preLaunchTask": "flix: debug demo"
}
```

**Launch**, no separate task needed:

```json
{
    "type": "flix",
    "name": "Flix: launch demo",
    "request": "launch",
    "program": "${workspaceFolder}/src/flix/DatalogYamlDemo.flix",
    "entryPoint": "demo",
    "flixCommand": ["${workspaceFolder}/scripts/flix-fork"]
}
```

Either way: install the adapter once (`./debug-adapter/install.sh`, then
reload VS Code), set a breakpoint directly in a `.flix` file's gutter, and
start the config from the Run and Debug panel.

## IntelliJ

One-time: install [LSP4IJ][lsp4ij] from the Marketplace and this project's
`flix.jetbrains.plugin` (see its own README for the build/sideload steps).

**Attach**: Run → Edit Configurations → + → Debug Adapter Protocol → Server
tab, select "Flix (--Xdebug attach)" → **Mappings tab, add `*.flix`**
(required once per configuration) → Configuration tab, Debug Mode: Attach,
host/port matching a JDWP agent you started yourself (e.g. via
`JAVA_TOOL_OPTIONS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005' ./scripts/flix-fork run --Xdebug --yes`).

**Launch**: right-click a `.flix` file in the editor or Project tool window
→ **"Debug '<file>'"** -- no manual configuration needed the first time; one
gets auto-created for you. There's no inline gutter/CodeLens icon for this
(unlike the "▶ Run" CodeLens above `def main()`, which comes from the Flix
language server itself) -- the context-menu entry is the way in.

Launch mode always uses `main()` as the entry point (no override available)
and defaults to `flix` on `PATH` as the command, unless the
`FLIX_DEBUG_COMMAND` environment variable is set -- export it (e.g.
`FLIX_DEBUG_COMMAND=/absolute/path/to/scripts/flix-fork`) **before**
launching the IDE, since it's read once when the adapter process starts.
This repo's own `flix` on `PATH` isn't `--Xdebug`-capable at all (only
`scripts/flix-fork`'s vendored jar is), so launch mode against this repo's
`.flix` files needs that variable set, or the manual Attach flow instead.

## Using the debugger once it's stopped

- **Stepping**: step over/into/out work normally; JDK-internal frames
  (`java.*`, Flix's own runtime/compiler packages) are filtered out of
  stepping and the initial class-watch, so you land on your own code.
- **Variables**: every field of the current frame's locals is shown,
  expandable generically via JDI reflection -- Flix's compiled
  representations aren't specially unwrapped except for records (see
  below), so nested Tag/Obj wrappers show their raw shape.
- **Evaluate expression**: supports dotted-path field access
  (`classes.v0.value`), method calls with literal `int`/`string`/`true`/
  `false`/`null` arguments (`tree.forEach(1)`, `name.substring(0, 5)`), and
  integer array indexing (`items[0]`). No operators, no nested expressions
  as call arguments -- that would mean compiling arbitrary Flix source
  against the running program, a different-scale problem.

## Understanding what you see

Flix's compiled representation leaks through the debugger fairly directly,
since none of this tooling has real Flix-language knowledge of your
program's types -- it's all generic JDI reflection over compiled JVM
classes. A few shapes come up constantly:

- **`Tag$Obj$...@<id> (ordinal=N)`**: an enum case. Every case of every
  enum in the program compiles to a `Tag$...` class shaped by its payload
  arity, shared structurally across unrelated enums -- so the class name
  alone never tells you *which* enum/case this is, only the `ordinal`
  (which case, in declaration order) and its fields (`v0`, `v1`, ...) do.
  Flix's built-in `List[a]` is `Nil` (ordinal 0) / `Cons` (ordinal 1, `v0`
  = head, `v1` = tail), and `Validation`/`Result`-shaped enums follow the
  same pattern.
- **`{label = value, ...}`**: a structural record, pretty-printed from
  Flix's `label`/`value`/`rest` chain encoding (every record uses this
  exact three-field-per-cell shape regardless of its own field names).
- **`anf$N`** in the variables list: a synthetic temporary from the
  compiler's `EffectBinder` phase, which hoists non-trivial subexpressions
  (typically function calls) into `let anf$N = <expr>` bindings so effect
  operations run on an empty JVM operand stack. If you see `anf` holding
  what looks like the result of some call in your source line, that's
  exactly what it is -- an intermediate result, not something you wrote.
- **`matchVar$N`**: a synthetic temporary from the `Simplifier` phase's
  `match`-expression lowering, holding the scrutinee once so the compiled
  decision tree (tag tests, field extraction) can reference it repeatedly
  instead of re-evaluating the matched expression per case.

None of these need to be understood to debug your own logic -- they're
compiler plumbing, safe to skip past in the Variables pane -- but they're
not bugs or noise either; they're doing real work, just not work you wrote.

## Troubleshooting

**Debug fails almost instantly, "Unset error message." / disconnects, no
breakpoint hit, no program output** (IntelliJ): two independent causes
produce this exact symptom, since LSP4IJ's Console only renders the DAP
*server* process's raw stdout/stderr, not the DAP protocol responses that
would otherwise explain what went wrong:

- The resolved flix command isn't `--Xdebug`-capable (see `FLIX_DEBUG_COMMAND`
  above) -- check `which flix` / `flix --version` in the same shell that
  launched the IDE.
- An existing run configuration already matching the file got reused as-is,
  mode and all (LSP4IJ prefers reusing a matching configuration over
  creating a fresh one) -- check Run \| Edit Configurations for a stale
  entry left in Attach mode with nothing listening on its port.

**Breakpoint stays unverified / never hits**: confirm the target was
actually built with `--Xdebug` (upstream Flix silently has no stratum at
all, so breakpoints fall back to whatever the ordinary `LineNumberTable`
reports -- usually wrong line numbers for a source-mapped language), and
that the JDWP agent's `suspend=y` is in effect so the target doesn't run
past your breakpoint before the debugger attaches.

[lsp4ij]: https://plugins.jetbrains.com/plugin/23257-lsp4ij
