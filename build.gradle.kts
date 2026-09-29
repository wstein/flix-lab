plugins {
    java
    groovy
    scala
    kotlin("jvm") version "2.4.10"
}

group = "dev.wstein.flix"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Scala configuration for Java 21 target
val scalaVersion = "3.8.4"
scala {
    zincVersion.set("1.9.1")
}

// The native CLI ports (see README.md's "Native ports" section) get their own source set rather
// than joining `main` -- sharing main's classpath (the flix-vendor/artifact jars, Jackson, ASM,
// ANTLR, ...) broke compilation outright: a root-package `Array` class somewhere in that huge,
// unrelated dependency graph shadowed `kotlin.Array` in src/kotlin/Main.kt. Isolating them also
// matches each port's design as a "single dependency-free file" -- see README.md and the plain
// javac/kotlinc/scalac instructions there, which reference these same src/{java,kotlin,scala}
// paths.
sourceSets {
    create("ports") {
        java.srcDir("src/java")
        kotlin.srcDir("src/kotlin")
        scala.srcDir("src/scala")
    }
    // Plain Java that Flix *calls*, as opposed to the ports above which reimplement Main.flix
    // standalone. It is packaged as a jar and put on the compiler's classpath through flix.toml's
    // [jar-dependencies], because the Flix compiler does not compile .java sources itself.
    //
    // Isolated for the same reason `ports` is: it must not inherit main's dependency graph.
    // The exported Flix facade is supplied as a compile-only stub below.
    create("javalib") {
        java.srcDir("src/javalib")
    }
    // Kotlin/Scala/Groovy/JRuby siblings of javalib's Greeter (see flix.toml's [jar-dependencies]
    // comment and each language's Greeter doc comment). One source set per language, each packaged
    // into its own jar by a `*libJar` task below -- kept out of javalib so javalib itself stays
    // dependency-free, and kept separate from `ports` since these are libraries Flix calls into,
    // not standalone CLI reimplementations.
    create("kotlinlib") {
        kotlin.srcDir("src/kotlinlib")
    }
    create("scalalib") {
        scala.srcDir("src/scalalib")
    }
    create("groovylib") {
        groovy.srcDir("src/groovylib")
    }
    // greeter.rb lives under src/jrubylib/resources (the default resources dir for this source
    // set's name) so it lands on the classpath next to Greeter.class, at the same relative path
    // Greeter.java loads it from via PathType.CLASSPATH.
    create("jrubylib") {
        java.srcDir("src/jrubylib/java")
    }
    // PortsCliTest lives here rather than the default `test` source set, which compiles against
    // `main`'s classpath -- the same toxic mix of unrelated dependencies that had to be kept out
    // of `ports` above. It only needs JUnit: it drives each port as a subprocess (see its own doc
    // comment), never touching the port classes at Java compile time.
    create("portsTest") {
        java.srcDir("src/portsTest/java")
    }
}

// Break the Flix/Java cycle: derive facade sources without resolving Java imports, compile those
// sources separately, then compile the Java greeter against their class files. Only javalib's own
// output goes into its jar; a generated stub must never be present at runtime.
val flixStubSources = layout.buildDirectory.dir("flix-stubs")
val flixStubClasses = layout.buildDirectory.dir("flix-stub-classes")
val generateFlixStubs by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates compile-only Java facades for exported Flix definitions."
    inputs.files(fileTree("src") { include("**/*.flix") })
    inputs.file("scripts/flix-fork")
    inputs.files(fileTree(rootDir) { include("flix-vendor-*.jar") })
    System.getenv("FLIX_FORK_JAR")?.let { inputs.file(it) }
    outputs.dir(flixStubSources)
    commandLine("scripts/flix-fork", "stubs", "--out", flixStubSources.get().asFile.absolutePath)
}

val compileFlixStubsJava by tasks.registering(JavaCompile::class) {
    dependsOn(generateFlixStubs)
    source(fileTree(flixStubSources) { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(flixStubClasses)
    options.release.set(21)
}

// Full debug information for the Flix-callable Java. Line numbers alone would let breakpoints bind
// while leaving the Variables pane showing arg0/arg1 placeholders instead of parameter names, which
// reads as a debugger fault rather than a missing compiler flag.
tasks.named<JavaCompile>("compileJavalibJava") {
    dependsOn(compileFlixStubsJava)
    classpath += files(flixStubClasses)
    options.compilerArgs.addAll(listOf("-g"))
}

// The jar flix.toml points at. Written into vendor/ alongside the other vendored jars so there is
// one place to look for "what is on the Flix classpath and where did it come from".
val javalibJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages src/javalib for the Flix compiler's classpath (see flix.toml)."
    archiveFileName.set("flixlab-javalib.jar")
    destinationDirectory.set(layout.projectDirectory.dir("vendor/javalib"))
    from(sourceSets.named("javalib").get().output)
    // The Flix package manager neither refreshes cached local file URLs nor downloads a missing
    // file URL. Keep its project-local copy current when this jar changes, so run/test use the real
    // Java class compiled above.
    doLast {
        copy {
            from(archiveFile)
            into(layout.projectDirectory.dir("lib/external"))
        }
    }
}

val kotlinlibJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages src/kotlinlib for the Flix compiler's classpath (see flix.toml)."
    archiveFileName.set("flixlab-kotlinlib.jar")
    destinationDirectory.set(layout.projectDirectory.dir("vendor/kotlinlib"))
    from(sourceSets.named("kotlinlib").get().output)
}

val scalalibJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages src/scalalib for the Flix compiler's classpath (see flix.toml)."
    archiveFileName.set("flixlab-scalalib.jar")
    destinationDirectory.set(layout.projectDirectory.dir("vendor/scalalib"))
    from(sourceSets.named("scalalib").get().output)
}

val groovylibJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages src/groovylib for the Flix compiler's classpath (see flix.toml)."
    archiveFileName.set("flixlab-groovylib.jar")
    destinationDirectory.set(layout.projectDirectory.dir("vendor/groovylib"))
    from(sourceSets.named("groovylib").get().output)
}

val jrubylibJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages src/jrubylib for the Flix compiler's classpath (see flix.toml)."
    archiveFileName.set("flixlab-jrubylib.jar")
    destinationDirectory.set(layout.projectDirectory.dir("vendor/jrubylib"))
    from(sourceSets.named("jrubylib").get().output)
}

// One place to (re)build everything flix.toml's [jar-dependencies] points at.
tasks.register("libJars") {
    group = "build"
    description = "Packages javalib and its Kotlin/Scala/Groovy/JRuby siblings for Flix's classpath."
    dependsOn(javalibJar, kotlinlibJar, scalalibJar, groovylibJar, jrubylibJar)
}

dependencies {
    // Scala 3 (latest)
    implementation("org.scala-lang:scala3-library_3:$scalaVersion")
    implementation("org.scala-lang:scala3-compiler_3:$scalaVersion")

    // Kotlin standard library
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")

    // Flix vendor dependencies
    implementation(files(rootProject.projectDir.resolve("flix-vendor-2026.07.24.1.jar")))
    implementation(files(rootProject.projectDir.resolve("artifact/flix-lab.jar")))

    // Maven dependencies from flix.toml
    implementation("com.fasterxml.jackson.core:jackson-core:2.21.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.5")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.21")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-smile:2.21.5")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.21.5")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.5")
    implementation("org.jetbrains:annotations:26.1.0")
    implementation("org.jspecify:jspecify:1.0.0")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-util:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.apache.commons:commons-text:1.15.0")
    implementation("org.apache.commons:commons-lang3:3.19.0")
    implementation("io.github.classgraph:classgraph:4.8.184")
    implementation("io.micrometer:micrometer-core:1.9.17")
    implementation("org.yaml:snakeyaml:2.6")
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    implementation("org.objenesis:objenesis:3.5")

    // Testing
    testImplementation("junit:junit:4.13.2")

    // ports/ isolated classpath: the Scala port needs scala3-library at runtime for
    // scala.util.CommandLineParser (see README.md's "Native ports" section); the Kotlin Gradle
    // plugin adds kotlin-stdlib to every Kotlin source set automatically.
    "portsImplementation"("org.scala-lang:scala3-library_3:$scalaVersion")

    "portsTestImplementation"("junit:junit:4.13.2")

    // kotlinlib/ isolated classpath: the Kotlin Gradle plugin adds kotlin-stdlib automatically, as
    // with `ports` above -- nothing to add here.

    // scalalib/ isolated classpath: same reason as `portsImplementation` above.
    "scalalibImplementation"("org.scala-lang:scala3-library_3:$scalaVersion")

    // groovylib/ isolated classpath: unlike the Kotlin plugin, Gradle's `groovy` plugin does not
    // add a Groovy dependency automatically -- without this, compileGroovylibGroovy fails outright.
    "groovylibImplementation"("org.apache.groovy:groovy:5.0.4")

    // jrubylib/ isolated classpath: Greeter.java's ScriptingContainer comes from here (see
    // src/jrubylib/java/dev/wstein/flixlab/jruby/Greeter.java). Not bundled into the jar the Jar
    // task below produces -- like kotlinlib/scalalib/groovylib's runtimes, it reaches Flix's
    // classpath separately via flix.toml's [mvn-dependencies].
    "jrubylibImplementation"("org.jruby:jruby-complete:10.0.4.0")
}

tasks {
    compileScala {
        scalaCompileOptions.additionalParameters = listOf(
            "-target:21",
            "-release:21"
        )
    }

    compileKotlin {
        // Configuration moved to top-level kotlin.compilerOptions
    }

    compileJava {
        options.release.set(21)
    }

    named<ScalaCompile>("compilePortsScala") {
        scalaCompileOptions.additionalParameters = listOf(
            "-target:21",
            "-release:21"
        )
    }

    named<JavaCompile>("compilePortsJava") {
        options.release.set(21)
    }

    named<ScalaCompile>("compileScalalibScala") {
        scalaCompileOptions.additionalParameters = listOf(
            "-target:21",
            "-release:21"
        )
    }

    named<JavaCompile>("compileJrubylibJava") {
        options.release.set(21)
    }

    named<GroovyCompile>("compileGroovylibGroovy") {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

// One run task per native CLI port (src/java, src/kotlin, src/scala -- see README.md's "Native
// ports" section), since each compiles to a differently-named entry-point class (Java's
// `public class Main` -> Main, Kotlin's top-level `fun main` -> MainKt, Scala's `@main def run`
// -> run) that a single mainClass could only target one of at a time.
// Pass a CLI argument with e.g. `./gradlew runKotlinPort -PappArgs=Ada`.
val portRuntimeClasspath = sourceSets.named("ports").get().runtimeClasspath
val portArgs = (findProperty("appArgs") as String?)?.split(" ") ?: emptyList()

// PortsCliTest (src/portsTest/java) spawns each port as a subprocess against this classpath (see
// its own doc comment for why it can't just call each Main directly). Wired into `check` so
// `./gradlew check`/`build` covers the ports the same way `test` covers `main`.
val portsTestSourceSet = sourceSets.named("portsTest").get()
tasks.register<Test>("testPorts") {
    group = "verification"
    description = "Smoke-tests the native CLI ports (src/java, src/kotlin, src/scala)."
    dependsOn("compilePortsJava", "compilePortsKotlin", "compilePortsScala")
    testClassesDirs = portsTestSourceSet.output.classesDirs
    classpath = portsTestSourceSet.runtimeClasspath
    systemProperty("portsClasspath", portRuntimeClasspath.asPath)
}

tasks.named("check") {
    dependsOn("testPorts")
}

tasks.register<JavaExec>("runJavaPort") {
    group = "application"
    description = "Runs the Java 21 port of Main.flix (src/java/Main.java)."
    mainClass.set("Main")
    classpath = portRuntimeClasspath
    args = portArgs
}

tasks.register<JavaExec>("runKotlinPort") {
    group = "application"
    description = "Runs the Kotlin port of Main.flix (src/kotlin/Main.kt)."
    mainClass.set("MainKt")
    classpath = portRuntimeClasspath
    args = portArgs
}

tasks.register<JavaExec>("runScalaPort") {
    group = "application"
    description = "Runs the Scala 3 port of Main.flix (src/scala/Main.scala)."
    mainClass.set("run")
    classpath = portRuntimeClasspath
    args = portArgs
}
