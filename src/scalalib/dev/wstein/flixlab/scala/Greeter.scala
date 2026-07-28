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
    decorate(subj)

  /** The subject of the greeting. Separate so it can be stepped into from `greeting`. */
  private def subject(): String = "Scala"

  // -- Scala 3 constructs that generate frames of their own -------------------------------------
  //
  // The three below exist for the debugger rather than for the greeting: `inline`, extension
  // methods and `given` each compile to something that does not correspond one-to-one with the
  // source a reader sees, which is exactly the shape that can mis-attribute a stack frame.
  //
  // The interop matrix asks whether frames like these corrupt the *Flix* positions underneath
  // them. They cannot claim a `.flix` source, but a debugger that mis-reads one frame can lose the
  // frames below it, and that is the failure worth having a fixture for. The greeting is unchanged
  // -- `decorate` still produces "Hello Scala!" -- so nothing downstream depends on this shape.

  /** Carried implicitly to [[shout]] rather than passed, so resolution happens at the call site. */
  final case class Punctuation(mark: String)

  given Punctuation = Punctuation("!")

  extension (subject: String)
    /** An extension method: compiled to a static method whose receiver is an ordinary parameter. */
    def shout(using punctuation: Punctuation): String =
      subject + punctuation.mark

  /**
   * Inlined into its caller, so these lines execute inside `greeting`'s frame rather than their
   * own -- the Scala analogue of the Kotlin `inline fun` covered by the same matrix row.
   */
  inline def decorate(subject: String)(using Punctuation): String =
    "Hello " + subject.shout
