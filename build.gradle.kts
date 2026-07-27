plugins {
    java
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
    // PortsCliTest lives here rather than the default `test` source set, which compiles against
    // `main`'s classpath -- the same toxic mix of unrelated dependencies that had to be kept out
    // of `ports` above. It only needs JUnit: it drives each port as a subprocess (see its own doc
    // comment), never touching the port classes at Java compile time.
    create("portsTest") {
        java.srcDir("src/portsTest/java")
    }
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
