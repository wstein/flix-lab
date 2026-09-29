# flix-lab

A small Flix command-line program that greets a name passed on the command
line.

## Usage

Build and verify the Java round trip, then run the packaged program with its
dependencies on one JVM classpath. Set `FLIX_FORK_JAR` to a current fork
assembly if no `flix-vendor-*.jar` is in the project root (see
`scripts/flix-fork`).

```console
./scripts/check-java-round-trip
runtime_cp="artifact/flix-lab.jar:$(find lib -type f -name '*.jar' -print | paste -sd: -)"
java -cp "$runtime_cp" Main             # Hello World! (after the greeter lines)
java -cp "$runtime_cp" Main Ada         # Hello, Ada!
java -cp "$runtime_cp" Main -h          # prints usage and exits
java -cp "$runtime_cp" Main --help      # same as -h
java -cp "$runtime_cp" Main --usage     # same as -h
```

The fork's current in-process `flix run` loader cannot resolve an exported Flix
facade from an external Java jar. The packaged JVM run above loads both on one
classpath.

| Flag                      | Description                      |
| ------------------------- | -------------------------------- |
| `-h`, `--help`, `--usage` | Show the usage message and exit. |

Passing an unrecognized option prints an error and the usage message, and
exits with status code 2.

## Native ports (Java 21, Kotlin, Scala 3)

[src/java/Main.java](src/java/Main.java), [src/kotlin/Main.kt](src/kotlin/Main.kt),
and [src/scala/Main.scala](src/scala/Main.scala) reimplement the same CLI
natively in each JVM language instead of calling
[src/flix/Main.flix](src/flix/Main.flix)'s `Util.GetOpt`-based parsing --
same observable behavior, no shared code, single dependency-free file each.

Plain compiler, no build step:

```console
javac --release 21 -d out src/java/Main.java && java -cp out Main Ada

kotlinc src/kotlin/Main.kt -include-runtime -d out/main.jar && java -jar out/main.jar Ada

mkdir -p out && scalac -d out src/scala/Main.scala
SCALA_HOME="$(brew --prefix scala)/libexec/maven2/org/scala-lang"  # adjust if not installed via Homebrew
java -cp "out:$(find "$SCALA_HOME" -iname 'scala3-library_3-*.jar' -o -iname 'scala-library-*.jar' | paste -sd: -)" run Ada
```

Unlike `javac`/`kotlinc`, `scalac -d out` requires `out` to already exist.
`java -cp out run` alone isn't enough either -- `@main`'s generated entry
point pulls in `scala.util.CommandLineParser` from the Scala runtime
library, which isn't on the classpath unless added explicitly (`scalac`
itself doesn't need it; only running the compiled class does).

Or via the root Gradle build ([build.gradle.kts](build.gradle.kts)), which
targets Java 21/Kotlin 2.4.10/Scala 3.8.4 and compiles the three ports in
their own `ports` source set -- isolated from the rest of the project's
dependencies (Jackson/ASM/the Flix build artifact/...), since those turned
out to collide with unqualified JVM types like `Array`/`String` when
compiled on the same classpath. `PortsCliTest`
([src/portsTest/java](src/portsTest/java)) smoke-tests each port's help
text, greeting, and error-exit-code behavior as a subprocess, wired into
`./gradlew check`:

```console
./gradlew runJavaPort -PappArgs=Ada
./gradlew runKotlinPort -PappArgs=Ada
./gradlew runScalaPort -PappArgs=Ada
./gradlew testPorts
```

## Polyglot Greeter siblings (Java, Kotlin, Scala, Groovy, JRuby)

Unlike the native ports above (standalone CLI reimplementations that never
call into Flix), [src/javalib/dev/wstein/flixlab/Greeter.java](src/javalib/dev/wstein/flixlab/Greeter.java)
and its four siblings --
[src/kotlinlib](src/kotlinlib/dev/wstein/flixlab/kotlin/Greeter.kt),
[src/scalalib](src/scalalib/dev/wstein/flixlab/scala/Greeter.scala),
[src/groovylib](src/groovylib/dev/wstein/flixlab/groovy/Greeter.groovy), and
[src/jrubylib](src/jrubylib/java/dev/wstein/flixlab/jruby/Greeter.java) --
are all called *from* [src/flix/Main.flix](src/flix/Main.flix), one call per
language. The Java greeter calls the exported `JavaGreeting.subject` facade
back in Flix, giving a Flix -> Java -> Flix path. The other four currently
return directly to their Flix callers. Every one of the five follows the same
shape: a public `greeting()` that assigns from a nested private `subject()` call, giving a
debugger something to Step Into, Step Over, and Step Out of (see the Java
Greeter's doc comment for why that shape, specifically). JRuby is the odd one
out: Ruby doesn't compile to a static method Flix can call directly, so its
`Greeter.java` only boots an embedded JRuby runtime via
`org.jruby.embed.ScriptingContainer`; the actual `subject`/`greeting` methods
live in [greeter.rb](src/jrubylib/resources/dev/wstein/flixlab/jruby/greeter.rb),
interpreted at call time.

Each sibling compiles in its own Gradle source set (`kotlinlib`, `scalalib`,
`groovylib`, `jrubylib` -- see build.gradle.kts) into its own jar under
`vendor/`, referenced by `flix.toml`'s `[jar-dependencies]`; each language's
runtime library reaches Flix's classpath separately via
`[mvn-dependencies]`, the same two-step wiring `javalib`'s jar already used.
Rebuild all five jars after editing any of them:

```console
./gradlew libJars
```

For the Java greeter, `javalibJar` first generates Flix facade stubs under
`build/flix-stubs`, compiles those into a separate compile-only directory, and
compiles Java against them. The jar contains only Java classes; the final Flix
build emits the real facade. The runtime check builds both stages, runs the
packaged program, and checks its Java greeting:

```console
./scripts/check-java-round-trip
# Java round trip passed: Hello Java via Flix!

java -cp "$runtime_cp" Main
# Hello Java via Flix!
# Hello Kotlin!
# Hello Scala!
# Hello Groovy!
# Hello JRuby!
# 41
# Hello World!
```

## Datalog + Java interop demo

[src/flix/DatalogYamlDemo.flix](src/flix/DatalogYamlDemo.flix) is a second, independent
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

[src/flix/JavaRewriteDemo.flix](src/flix/JavaRewriteDemo.flix) is a third entry point
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
