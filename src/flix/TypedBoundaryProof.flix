/// A small executable probe for ADR 3's proposed result-side conversion.
/// This is a library-only experiment: the existing @Export compiler path emits the JVM facade.
pub mod TypedBoundaryProof {

    import java.lang.Integer
    import java.util.{List => JList}

    pub trait JavaResult[t: Type] {
        type Out[t]: Type
        type Aef[t]: Eff = {}
        pub def toJava(x: t): JavaResult.Out[t] \ JavaResult.Aef[t]
    }

    instance JavaResult[Int32] {
        type Out = Integer
        pub def toJava(x: Int32): Integer = Int32.valueOf(x)
    }

    instance JavaResult[List[a]] with JavaResult[a] {
        type Out = JList[JavaResult.Out[a]]
        type Aef = IO + JavaResult.Aef[a]
        pub def toJava(xs: List[a]): JList[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
            Adaptor.toList(List.map(JavaResult.toJava, xs))
    }

    @Export
    pub def boxedInts(start: Int32): JList[Integer] \ IO =
        JavaResult.toJava(start :: (start + 1) :: (start + 2) :: Nil)
}
