package dev.wstein.flixlab.scala

/** Scala sibling of `dev.wstein.flixlab.Greeter` -- see that class's doc comment for why the
  * nested-call shape matters for a debug session, and build.gradle.kts's `scalalib` source set for
  * how this reaches Flix's classpath (packaged separately from javalib so javalib stays
  * dependency-free).
  *
  * A Scala `object` with no companion class gets a real static forwarder generated in a same-named
  * `Greeter` class, so `Greeter.greeting()` is callable from Java/Flix exactly like the other three
  * siblings -- no annotation needed here, unlike Kotlin's `@JvmStatic`.
  */
object Greeter:

  def greeting(): String =
    // Named differently from `subject()` -- unlike Java's `String subject = subject();`, Scala
    // resolves the whole block's `subject` binding before the RHS, so a same-named `val` would
    // shadow the method call it's initialized from ("recursive value subject needs type").
    val subj = subject()
    s"Hello $subj!"

  /** The subject of the greeting. Separate so it can be stepped into from `greeting`. */
  private def subject(): String = "Scala"
