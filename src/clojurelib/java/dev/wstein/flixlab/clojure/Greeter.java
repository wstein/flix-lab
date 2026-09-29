package dev.wstein.flixlab.clojure;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

/** Loads the Clojure greeter from this jar and exposes a static method for Flix interop. */
public final class Greeter {

    private Greeter() {
    }

    public static String greeting() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("dev.wstein.flixlab.clojure.greeter"));
        IFn greeting = Clojure.var("dev.wstein.flixlab.clojure.greeter", "greeting");
        return (String) greeting.invoke();
    }
}
