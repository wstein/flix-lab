package dev.wstein.flixlab;

import dev.flix.gen.JavaGreeting;

/**
 * Plain Java called from Flix, so a debug session has somewhere to step into.
 *
 * <p>Deliberately more than a one-liner. A single {@code return "Hello Java!"} would verify that
 * the call works and nothing else: Step Into and Step Out would land on the same line, Step Over
 * would have nothing to step over, and there would be no local to inspect. The shape below gives
 * each of those something to do -- a local assigned from a nested call, then used.
 *
 * <p>Compiled with full debug information (see the {@code javalib} source set in build.gradle.kts).
 * Without {@code -g} the locals have no names in the class file and the Variables pane shows
 * {@code arg0}-style placeholders, which looks like a debugger fault rather than a build setting.
 */
public final class Greeter {

    private Greeter() {
    }

    /** Returns the greeting, via a nested call so Step Into has a target. */
    public static String greeting() {
        String subject = subject();
        String message = "Hello " + subject + "!";
        return message;
    }

    /**
     * The subject of the greeting. Separate so it can be stepped into from {@link #greeting()}.
     *
     * <p>Reaches for a configured value first and falls back. The failure is deliberate: a caught
     * exception is what an exception breakpoint needs a target for, and one thrown on a normal run
     * proves the request is honoured in a Flix-launched JVM without needing a failing program. The
     * fallback calls an exported Flix facade, completing the Flix -> Java -> Flix path.
     */
    private static String subject() {
        try {
            return configuredSubject();
        } catch (IllegalStateException unconfigured) {
            return JavaGreeting.subject("Java");
        }
    }

    /** Always fails: nothing configures a subject. See {@link #subject()}. */
    private static String configuredSubject() {
        throw new IllegalStateException("no subject configured");
    }
}
