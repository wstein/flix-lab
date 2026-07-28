package dev.wstein.flixlab.kotlin

/**
 * Kotlin sibling of [dev.wstein.flixlab.Greeter] -- see that class's doc comment for why the
 * nested-call shape matters for a debug session, and build.gradle.kts's `kotlinlib` source set for
 * how this reaches Flix's classpath (packaged separately from javalib so javalib stays
 * dependency-free).
 *
 * `@JvmStatic` is required, not decorative: without it `greeting()` would only exist as an
 * instance method on the `Greeter` singleton (reachable from Java/Flix via `Greeter.INSTANCE`),
 * not as the real static method `Greeter.greeting()` that mirrors the other three siblings' shape.
 */
object Greeter {

    @JvmStatic
    fun greeting(): String {
        val subject = subject()
        return decorate(subject) { "Hello $it!" }
    }

    /** The subject of the greeting. Separate so it can be stepped into from [greeting]. */
    private fun subject(): String = "Kotlin"

    /**
     * Inline function taking a lambda, present purely as a debugger fixture.
     *
     * Row 3 of the JVM-interop matrix asks whether Kotlin inline-function and lambda frames steal
     * or mis-map Flix positions. Testing it needs a fixture that produces those frames, and the
     * ordinary `greeting()` shape does not: an inline function is *copied into its caller*, so its
     * body has no frame of its own and its lines land inside `greeting` under Kotlin's own SMAP
     * `KotlinDebug` stratum.
     *
     * That is precisely the arrangement that could go wrong. Flix also depends on a non-default
     * stratum, and both are resolved by position managers composed into one debug process, so a
     * fixture where the two meet is worth having. Stepping here should show Kotlin lines; returning
     * to Flix should show Flix ones.
     *
     * Keep it `inline`. Dropping the modifier makes this an ordinary call, the frames disappear,
     * and the row stops testing anything without failing.
     */
    private inline fun decorate(subject: String, format: (String) -> String): String {
        val decorated = format(subject)
        return decorated
    }
}
