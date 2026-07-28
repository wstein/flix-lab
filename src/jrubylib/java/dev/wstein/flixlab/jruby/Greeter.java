package dev.wstein.flixlab.jruby;

import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;

/**
 * JRuby sibling of {@code dev.wstein.flixlab.Greeter}. Unlike the Kotlin/Scala/Groovy siblings,
 * Ruby doesn't compile ahead of time into a static method Flix can call directly, so this class is
 * only a thin boot shim: it starts an embedded JRuby runtime via {@link ScriptingContainer} and
 * runs greeter.rb (packaged alongside this class -- see build.gradle.kts's {@code jrubylib} source
 * set), which is where the actual nested-call shape (see the Java Greeter's doc comment) lives.
 */
public final class Greeter {

    private static final String SCRIPT_PATH = "dev/wstein/flixlab/jruby/greeter.rb";

    private Greeter() {
    }

    public static String greeting() {
        ScriptingContainer container = new ScriptingContainer();
        try {
            Object result = container.runScriptlet(PathType.CLASSPATH, SCRIPT_PATH);
            return result.toString();
        } finally {
            container.terminate();
        }
    }
}
