import java.nio.file.{Files => Files}
import java.nio.file.{Path => JPath}
import org.openrewrite.{ExecutionContext => ExecutionContext}
import org.openrewrite.{InMemoryExecutionContext => InMemoryExecutionContext}
import org.openrewrite.{SourceFile => SourceFile}
import org.openrewrite.java.{JavaParser => JavaParser}
import org.openrewrite.java.tree.{J$ClassDeclaration => ClassDeclaration}
import org.openrewrite.java.tree.{J$CompilationUnit => CompilationUnit}
import org.openrewrite.java.tree.{J$MethodDeclaration => MethodDeclaration}
import org.openrewrite.java.tree.{Statement => Statement}
import org.openrewrite.java.tree.{TypeTree => TypeTree}

///
/// A class or interface declaration extracted from parsed Java source, via
/// OpenRewrite's Java LST (Lossless Semantic Tree).
///
pub type alias ClassInfo = {
    name       = String,
    extends    = Option[String],
    implements = List[String],
    methods    = List[String]
}

///
/// Reads the text file at `path` using plain Java IO
/// (`java.nio.file.Files`).
///
def readSource(path: String): String \ IO =
    Files.readString(JPath.of(path))

///
/// Parses `source` (a fragment of Java source code) into its compilation
/// units, using a fork of OpenRewrite's `rewrite-java` parser (vendored
/// under `vendor/rewrite/`, since it isn't published to Maven Central --
/// see `vendor/README.md`).
///
def parseJava(source: String): List[CompilationUnit] \ IO = region rc {
    let parser: JavaParser = JavaParser.fromJavaVersion().build();
    let ctx: ExecutionContext = checked_cast(new InMemoryExecutionContext());
    let stream = parser.parse(ctx, source);
    let it: Iterator[SourceFile, rc, rc] = Adaptor.fromStreamToIterator(rc, stream);
    let sourceFiles: List[SourceFile] = Iterator.toList(it);
    sourceFiles
        |> List.filter(sf -> sf instanceof CompilationUnit)
        |> List.map(sf -> unchecked_cast(sf as CompilationUnit))
}

///
/// Returns the simple name of a `TypeTree` (e.g. the identifier after
/// `extends`/`implements`).
///
def typeTreeName(t: TypeTree): String \ IO =
    t.toString()

///
/// Returns the names of the methods declared directly in the body of `cd`
/// (interface methods included, whether abstract or with a body).
///
def declaredMethods(cd: ClassDeclaration): List[String] \ IO =
    let statements: List[Statement] = Adaptor.fromList(cd.getBody().getStatements());
    statements
        |> List.filter(s -> s instanceof MethodDeclaration)
        |> List.map(s -> (unchecked_cast(s as MethodDeclaration)).getSimpleName())

///
/// Extracts a `ClassInfo` from a single class/interface declaration.
///
def classInfo(cd: ClassDeclaration): ClassInfo \ IO =
    let extends = Object.toOption(cd.getExtends()) |> Option.map(typeTreeName);
    let implements = Object.toOption(cd.getImplements()) |> Option.map(Adaptor.fromList) |> Option.getWithDefault(Nil) |> List.map(typeTreeName);
    { name = cd.getSimpleName(), extends = extends, implements = implements, methods = declaredMethods(cd) }

///
/// Extracts a `ClassInfo` for every class/interface declared across `units`.
///
def allClassInfo(units: List[CompilationUnit]): List[ClassInfo] \ IO =
    units
        |> List.flatMap(cu -> Adaptor.fromList(cu.getClasses()))
        |> List.map(classInfo)

///
/// The `Class(name)` facts derived from `classes`.
///
def classFacts(classes: List[ClassInfo]): List[String] =
    List.map(c -> c#name, classes)

///
/// The `Extends(subclass, superclass)` facts derived from `classes`.
///
def extendsFacts(classes: List[ClassInfo]): List[(String, String)] =
    List.filterMap(c -> Option.map(sup -> (c#name, sup), c#extends), classes)

///
/// The `Implements(class, interface)` facts derived from `classes`.
///
def implementsFacts(classes: List[ClassInfo]): List[(String, String)] =
    List.flatMap(c -> List.map(iface -> (c#name, iface), c#implements), classes)

///
/// The `DeclaresMethod(class, method)` facts derived from `classes`.
///
def declaresMethodFacts(classes: List[ClassInfo]): List[(String, String)] =
    List.flatMap(c -> List.map(m -> (c#name, m), c#methods), classes)

///
/// Builds the Datalog program that computes, from `classes`:
///
/// * `Inherits(sub, sup)` -- the transitive closure of `extends`/`implements`,
///   i.e. every class or interface a class derives from, directly or not.
/// * `AvailableMethod(cls, method)` -- every method callable on `cls`,
///   whether declared directly or inherited from an ancestor.
///
/// See: https://doc.flix.dev/fixpoints.html#injecting-facts-into-datalog
///
def classHierarchy(classes: List[ClassInfo]): #{
    Inherits(String, String), AvailableMethod(String, String) | r
} =
    let cs = inject classFacts(classes) into Class/1;
    let es = inject extendsFacts(classes) into Extends/2;
    let is = inject implementsFacts(classes) into Implements/2;
    let ms = inject declaresMethodFacts(classes) into DeclaresMethod/2;
    let rules = #{
        Inherits(sub, sup) :- Extends(sub, sup).
        Inherits(sub, sup) :- Implements(sub, sup).
        Inherits(sub, sup) :- Extends(sub, mid), Inherits(mid, sup).
        Inherits(sub, sup) :- Implements(sub, mid), Inherits(mid, sup).
        AvailableMethod(cls, m) :- DeclaresMethod(cls, m).
        AvailableMethod(cls, m) :- Inherits(cls, sup), DeclaresMethod(sup, m).
    };
    solve cs, es, is, ms, rules project Inherits, AvailableMethod

///
/// Demonstrates parsing a Java source module with a fork of OpenRewrite's
/// Java LST parser, extracting facts about its classes, interfaces,
/// `extends`/`implements` relationships and declared methods via plain
/// Java interop, and injecting those facts into a Datalog program that
/// computes the full inheritance chain and the set of methods available
/// on each class (own and inherited).
///
pub def rewriteDemo(): Unit \ IO =
    let src = readSource("resources/rewrite-sample/Animals.java");
    let units = parseJava(src);
    let classes = allClassInfo(units);
    println("Parsed ${List.length(classes)} class/interface declaration(s) from ${List.length(units)} compilation unit(s) in resources/rewrite-sample/Animals.java.");
    println("");

    let hierarchy = classHierarchy(classes);

    let inherits = query hierarchy select (sub, sup) from Inherits(sub, sup);
    println("Inheritance (class -> ancestor):");
    inherits |> Vector.forEach(match (sub, sup) -> println("  ${sub} -> ${sup}"));
    println("");

    println("Available methods (own and inherited):");
    foreach (c <- classes) {
        let name = c#name;
        let methods = query hierarchy select method from AvailableMethod(name, method);
        println("  ${name}: ${methods |> Vector.toList}")
    }
