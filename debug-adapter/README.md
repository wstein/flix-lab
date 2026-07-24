# flix-debug (minimal)

A minimal [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/)
server that lets VS Code set breakpoints directly in `.flix` files and debug
a running Flix program.

## How it works

Upstream Flix compiles to plain JVM bytecode with no way for a debugger to
know it came from `.flix` source. [wstein/flix-fork](https://github.com/wstein/flix-fork)'s
`--Xdebug` flag closes that gap: it emits a `LocalVariableTable`, real
per-statement line numbers, and a JSR-45 `SourceDebugExtension` (SMAP)
declaring a `"Flix"` stratum, so the JVM's own debug machinery
(`com.sun.jdi`) can resolve breakpoints and locals against real `.flix`
files and line numbers -- confirmed directly with `jdb -attach` before this
adapter was written (see the parent project's README).

The gap this fills is narrower: VS Code's generic "Debugger for Java"
extension only resolves breakpoints against `.java` source, so it can't use
that stratum. This adapter is a thin, purpose-built bridge instead:
- [src/FlixDebugAdapter.java](src/FlixDebugAdapter.java) speaks DAP over
  stdio (a hand-rolled JSON codec -- no dependencies to fetch) and backs it
  with `com.sun.jdi`, attaching to the JDWP port a `flix run --Xdebug`
  process is listening on.
- `setBreakpoints` resolves a `.flix` file + line against the `"Flix"`
  stratum (falling back to the class's own default stratum for classes with
  no cross-file inlining, which don't get an SMAP at all -- their ordinary
  `LineNumberTable` already holds real `.flix` line numbers). Classes not
  loaded yet get a deferred breakpoint via a `ClassPrepareRequest`, exactly
  like `jdb` does.
- Only `threads`/`stackTrace`/`scopes`/`variables`/`continue`/`next`/
  `stepIn`/`stepOut`/`pause` are implemented -- enough to hit a breakpoint
  and inspect the call stack and locals, not a general-purpose Java debugger.

## Install (unpublished, local extension)

```console
ln -s "$(pwd)/debug-adapter" ~/.vscode/extensions/flix-debug
```

Then reload VS Code (Developer: Reload Window). No build step -- the
adapter is launched via `java`'s single-file source-code execution
(`java --add-modules jdk.jdi src/FlixDebugAdapter.java`, wired up in
[bin/flix-debug-adapter](bin/flix-debug-adapter)), so editing
`FlixDebugAdapter.java` takes effect on the next debug session with no
recompile step.

## Use

See the parent project's README for the full setup (the `--Xdebug` fork
jar, `scripts/flix-fork`, the background tasks that start each example
suspended under JDWP). Once this extension is installed, add `"type":
"flix"` attach configurations to `.vscode/launch.json` instead of `"type":
"java"` ones, e.g.:

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

Set a breakpoint directly in the `.flix` file's gutter before starting --
the target JVM is suspended at startup and won't run any code until this
adapter attaches and resumes it.

## Testing without VS Code

[test/dap_client_test.py](test/dap_client_test.py) drives the adapter
through a full session (initialize, attach, setBreakpoints,
configurationDone, wait for the breakpoint to hit, stackTrace/scopes/
variables, continue, disconnect) via the same DAP messages VS Code would
send. Start a suspended example first (see the parent README), then:

```console
./debug-adapter/test/dap_client_test.py "$(pwd)/src/DatalogYamlDemo.flix" 209
```

## Known limitations

- Only tested against `wstein/flix-fork`'s `--Xdebug` output; upstream Flix
  has no stratum info, so breakpoints there would fall back to whatever the
  JVM's ordinary `LineNumberTable` reports (usually wrong line numbers for
  a source-mapped language).
- `variables` formats object values the way `jdb` does
  (`TypeName@uniqueId`), not recursively -- no expand/drill-down into
  nested fields.
- No conditional breakpoints, logpoints, watch expressions, or exception
  breakpoints.
- `next`/`stepIn`/`stepOut` don't filter out JDK-internal frames, so
  stepping can briefly surface unrelated stack frames.
