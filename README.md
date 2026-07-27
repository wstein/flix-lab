# flix-lab

A small Flix command-line program that greets a name passed on the command
line.

## Usage

```console
flix run                # Hello World!
flix run -- Ada          # Hello, Ada!
flix run -- -h            # prints usage and exits
flix run -- --help        # same as -h
flix run -- --usage       # same as -h
```

| Flag                      | Description                      |
| ------------------------- | -------------------------------- |
| `-h`, `--help`, `--usage` | Show the usage message and exit. |

Passing an unrecognized option prints an error and the usage message, and
exits with status code 2.

## Datalog + Java interop demo

[src/DatalogYamlDemo.flix](src/DatalogYamlDemo.flix) is a second, independent
entry point (`demo`) that demonstrates:

* Reading a text file with plain Java IO (`java.nio.file.Files`), no
  third-party library.
* Building and populating `java.util.ArrayList`/`java.util.HashMap`
  structures directly from Flix to parse the file.
* [Injecting](https://doc.flix.dev/fixpoints.html#injecting-facts-into-datalog)
  the parsed facts into a Datalog program and querying it.

It reads [resources/people.yaml](resources/people.yaml), a simplified
YAML-like family tree of 106 people across 8 families that all descend from a
shared pair of common ancestors ("Adam" and "Eve"), and computes:

* `Father`/`Mother` -- each person's two parents, taken directly from the file.
* `Grandfather`/`Grandmother` -- each person's grandparents, joining
  `Father`/`Mother` one level up through either parent.
* `Ancestor` -- the transitive closure of "has a parent", i.e. every ancestor
  above a person, not just their immediate parents.

It also dumps the full tree, from the common ancestors down through every
descendant, using plain Flix recursion over the parsed facts (not Datalog).

Run it with:

```console
flix run --entrypoint demo
```

## Java LST + Datalog demo

[src/JavaRewriteDemo.flix](src/JavaRewriteDemo.flix) is a third entry point
(`rewriteDemo`) that demonstrates deriving Datalog facts from a real Java
source module, parsed with a fork of
[OpenRewrite](https://docs.openrewrite.org/)'s Java LST (Lossless Semantic
Tree) parser -- `org.openrewrite:rewrite-java-21:0.1.0-SNAPSHOT`, vendored
under [vendor/rewrite/](vendor/rewrite/) since it isn't published to Maven
Central (see [vendor/README.md](vendor/README.md) for how it's wired into
`flix.toml`, and why).

It parses [resources/rewrite-sample/Animals.java](resources/rewrite-sample/Animals.java),
walks the resulting class declarations via plain Java interop (no visitor
subclassing -- Flix can't subclass OpenRewrite's abstract `TreeVisitor`), and
injects `Class`/`Extends`/`Implements`/`DeclaresMethod`/`Doc`/`ClassAnnotation`/
`MethodAnnotation` facts into a Datalog program that computes:

* `Inherits` -- the transitive closure of `extends`/`implements`.
* `AvailableMethod` -- every method callable on a class, declared directly or
  inherited from an ancestor class or interface.
* `Domesticated`/`KnowsTrick` -- classes annotated (directly or via an
  ancestor) `@Domesticated`, or with a method annotated `@Trick`, demonstrating
  rule-derived facts from `@Annotation`s combined with `Inherits`.

Javadoc comments on classes and methods are extracted too (OpenRewrite parses
`/** ... */` into a structured `Javadoc.DocComment` tree, not a flat string)
and printed alongside each class/method, along with each method's `@param`/
`@return` tags (`ParamDoc`/`ReturnDoc` facts).

The vendored jars aren't committed to git; fetch them once first:

```console
./vendor/setup-rewrite.sh
flix run --entrypoint rewriteDemo
```

## Building

```console
flix build
flix test
```

## Debugging an example

The three examples (`main`, `demo`, `rewriteDemo`) can be stepped through
**with real breakpoints set directly in the `.flix` editor gutter**, in
either VS Code or an IntelliJ-based IDE, using `--Xdebug` from a custom
build of [wstein/flix-fork](https://github.com/wstein/flix-fork) --
upstream Flix has no such flag. See
[docs/debugging.md](docs/debugging.md) for the full setup (both editors,
attach and launch modes), what to expect once stopped (variables, stepping,
evaluate expressions), how to read Flix's compiled representation in the
debugger, and troubleshooting. [debug-adapter/](debug-adapter/) has the
adapter's own implementation details
([FlixDebugAdapter.java](debug-adapter/src/FlixDebugAdapter.java)).
