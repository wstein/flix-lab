# ADR 3 typed-boundary probe

Run `./scripts/check-typed-boundary-prototype` with `FLIX_FORK_JAR` pointing at the merged
`dev0.77.0` fork's assembly jar. The script builds only compile-time facade stubs, compiles
`BoundaryCaller.java` against those stubs, builds the real Flix jar, checks the emitted JVM
descriptor and generic `Signature` attribute, and runs the original Java caller without any stub
class on its runtime classpath.

This proves three narrow points using the **current `@Export` implementation**:

1. A `JavaResult[List[a]]` instance can recursively apply an element conversion to `Int32` values.
2. A Flix export with an explicit `java.util.List[java.lang.Integer]` return type emits
   `List<Integer>` in the facade's generic `Signature` attribute.
3. A Java caller compiled against the staged stub links to and receives boxed `Integer` values
   from the real facade.

It does **not** prove the ADR's proposed `export mod ... as` syntax, synthesized wrappers,
automatic ABI selection, or removal of `@Export`. A first attempt to declare the export's return
type as `JavaResult.Out[List[Int32]]` was rejected by Flix: associated types may only be applied
to a type variable, not a concrete `List[Int32]`. The probe uses an explicit `JList[Integer]`
signature; the language/compiler design must account for that restriction.
