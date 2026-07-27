# Ports of src/Main.flix

Same CLI program (`-h`/`--help`/`--usage` prints usage and exits `0`; an
unrecognized option prints an error plus usage and exits `2`; otherwise the
first positional argument is greeted, or `Hello World!` if there isn't
one), reimplemented natively in three JVM languages instead of calling
[src/Main.flix](../src/Main.flix)'s `Util.GetOpt`-based parsing. Each is a
single dependency-free file -- no build system, just the plain compiler.

## Java 21

```console
javac --release 21 -d out ports/java/Main.java
java -cp out Main Ada
```

## Kotlin

```console
kotlinc ports/kotlin/Main.kt -include-runtime -d out/main.jar
java -jar out/main.jar Ada
```

## Scala 3

```console
mkdir -p out
scalac -d out ports/scala/Main.scala
SCALA_HOME="$(brew --prefix scala)/libexec/maven2/org/scala-lang"  # adjust if not installed via Homebrew
java -cp "out:$(find "$SCALA_HOME" -iname 'scala3-library_3-*.jar' -o -iname 'scala-library-*.jar' | paste -sd: -)" run Ada
```

Unlike `javac`/`kotlinc`, `scalac -d out` requires `out` to already exist.
`java -cp out run` alone isn't enough either -- `@main`'s generated entry
point pulls in `scala.util.CommandLineParser` from the Scala runtime
library, which isn't on the classpath unless added explicitly (`scalac`
itself doesn't need it; only running the compiled class does).
