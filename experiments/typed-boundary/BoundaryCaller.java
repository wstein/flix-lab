package dev.wstein.flixlab.boundary;

import dev.flix.gen.TypedBoundaryProof;
import java.util.List;

/** Compiled against only the generated facade stub, then run against the real Flix class. */
public final class BoundaryCaller {
    private BoundaryCaller() {}

    public static void main(String[] args) {
        List<Integer> values = TypedBoundaryProof.boxedInts(40);
        if (!values.equals(List.of(40, 41, 42))) {
            throw new AssertionError("Unexpected converted values: " + values);
        }
        for (Object value : values) {
            if (!(value instanceof Integer)) {
                throw new AssertionError("Element is not a boxed Integer: " + value);
            }
        }
        System.out.println("typed boundary prototype passed: " + values);
    }
}
