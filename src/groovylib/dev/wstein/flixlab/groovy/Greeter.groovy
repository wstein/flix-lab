package dev.wstein.flixlab.groovy

/**
 * Groovy sibling of {@code dev.wstein.flixlab.Greeter} -- see that class's doc comment for why the
 * nested-call shape matters for a debug session, and build.gradle.kts's {@code groovylib} source
 * set for how this reaches Flix's classpath (packaged separately from javalib so javalib stays
 * dependency-free).
 */
final class Greeter {

    private Greeter() {
    }

    static String greeting() {
        String subject = subject()
        "Hello ${subject}!"
    }

    /** The subject of the greeting. Separate so it can be stepped into from {@link #greeting()}. */
    private static String subject() {
        "Groovy"
    }
}
