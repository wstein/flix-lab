import java.nio.file.{Files => Files}
import java.nio.file.{Path => JPath}
import org.openrewrite.{ExecutionContext => ExecutionContext}
import org.openrewrite.{InMemoryExecutionContext => InMemoryExecutionContext}
import org.openrewrite.{SourceFile => SourceFile}
import org.openrewrite.java.{JavaParser => JavaParser}
import org.openrewrite.java.tree.{Comment => Comment}
import org.openrewrite.java.tree.{J => J}
import org.openrewrite.java.tree.{J$Annotation => Annotation}
import org.openrewrite.java.tree.{J$ClassDeclaration => ClassDeclaration}
import org.openrewrite.java.tree.{J$ClassDeclaration$Kind$Type => KindType}
import org.openrewrite.java.tree.{J$CompilationUnit => CompilationUnit}
import org.openrewrite.java.tree.{J$MethodDeclaration => MethodDeclaration}
import org.openrewrite.java.tree.{Javadoc => Javadoc}
import org.openrewrite.java.tree.{Javadoc$DocComment => DocComment}
import org.openrewrite.java.tree.{Javadoc$Text => JavadocText}
import org.openrewrite.java.tree.{Statement => Statement}
import org.openrewrite.java.tree.{TextComment => TextComment}
import org.openrewrite.java.tree.{TypeTree => TypeTree}

///
/// A method declaration extracted from parsed Java source, via OpenRewrite's
/// Java LST (Lossless Semantic Tree).
///
pub type alias MethodInfo = {
    name        = String,
    doc         = Option[String],
    annotations = List[String]
}

///
/// A class or interface declaration extracted from parsed Java source, via
/// OpenRewrite's Java LST (Lossless Semantic Tree).
///
pub type alias ClassInfo = {
    name        = String,
    doc         = Option[String],
    extends     = Option[String],
    implements  = List[String],
    annotations = List[String],
    methods     = List[MethodInfo]
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
/// Turns a sequence of lines (e.g. from a multi-line Javadoc body) into a
/// single human-readable line, trimming each and dropping blanks.
///
def joinLines(raw: String): String =
    String.lines(raw)
        |> List.map(String.trim)
        |> List.filter(l -> not String.isEmpty(l))
        |> String.intercalate(" ")

///
/// Returns the plain-text content of a Javadoc comment's body, ignoring
/// block/inline tags (`@param`, `{@link ...}`, etc.) -- just the `Text`
/// segments, which is enough for a one-line human-readable summary.
///
def javadocText(dc: DocComment): String \ IO =
    let body: List[Javadoc] = Adaptor.fromList(dc.getBody());
    body
        |> List.filter(j -> j instanceof JavadocText)
        |> List.map(j -> (unchecked_cast(j as JavadocText)).getText())
        |> String.intercalate(" ")
        |> joinLines

///
/// Returns the text of a single `Comment`, whether it's structured
/// Javadoc (`/** ... */`, parsed into a `Javadoc.DocComment` tree) or a
/// plain block/line comment (a flat `TextComment`).
///
def commentText(c: Comment): Option[String] \ IO =
    if (c instanceof DocComment)
        Some(javadocText(unchecked_cast(c as DocComment)))
    else if (c instanceof TextComment)
        Some(joinLines((unchecked_cast(c as TextComment)).getText()))
    else
        None

///
/// Returns the text of the doc comment attached to `node`, if any. Every
/// `J` tree node carries its comments via `getComments()`; we treat the
/// first one found as the doc comment.
///
def docComment(node: J): Option[String] \ IO =
    Adaptor.fromList(node.getComments())
        |> List.filterMap(commentText)
        |> List.head

///
/// Returns the simple names of a list of annotations (e.g. `Trick` for
/// `@Trick`).
///
def annotationNames(annotations: List[Annotation]): List[String] \ IO =
    List.map(a -> a.getSimpleName(), annotations)

///
/// Returns the methods declared directly in the body of `cd` (interface
/// methods included, whether abstract or with a body), together with
/// their doc comments and annotations.
///
def declaredMethods(cd: ClassDeclaration): List[MethodInfo] \ IO =
    let statements: List[Statement] = Adaptor.fromList(cd.getBody().getStatements());
    statements
        |> List.filter(s -> s instanceof MethodDeclaration)
        |> List.map(s -> {
            let md: MethodDeclaration = unchecked_cast(s as MethodDeclaration);
            let annotationList: List[Annotation] = Adaptor.fromList(md.getLeadingAnnotations());
            { name = md.getSimpleName(), doc = docComment(checked_cast(md)), annotations = annotationNames(annotationList) }
        })

///
/// Extracts a `ClassInfo` from a single class/interface declaration.
///
def classInfo(cd: ClassDeclaration): ClassInfo \ IO =
    let extends = Object.toOption(cd.getExtends()) |> Option.map(typeTreeName);
    let implements = Object.toOption(cd.getImplements()) |> Option.map(Adaptor.fromList) |> Option.getWithDefault(Nil) |> List.map(typeTreeName);
    let annotationList: List[Annotation] = Adaptor.fromList(cd.getLeadingAnnotations());
    { name = cd.getSimpleName(), doc = docComment(checked_cast(cd)), extends = extends, implements = implements, annotations = annotationNames(annotationList), methods = declaredMethods(cd) }

///
/// `true` if `cd` is an `@interface` (annotation type) declaration, as
/// opposed to a class, interface, enum, or record.
///
def isAnnotationType(cd: ClassDeclaration): Bool \ IO =
    let kind: KindType = cd.getKind();
    kind.toString() == "Annotation"

///
/// Extracts a `ClassInfo` for every class/interface declared across
/// `units`, excluding `@interface` (annotation type) declarations -- those
/// describe the annotations used elsewhere, not the class hierarchy itself.
///
def allClassInfo(units: List[CompilationUnit]): List[ClassInfo] \ IO =
    units
        |> List.flatMap(cu -> Adaptor.fromList(cu.getClasses()))
        |> List.filter(cd -> not isAnnotationType(cd))
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
    List.flatMap(c -> List.map(m -> (c#name, m#name), c#methods), classes)

///
/// The `Doc(name, text)` facts for every documented class/interface or
/// method (qualified as `Class.method`) in `classes`.
///
def docFacts(classes: List[ClassInfo]): List[(String, String)] =
    let classDocs = List.filterMap(c -> Option.map(d -> (c#name, d), c#doc), classes);
    let methodDocs = List.flatMap(c -> List.filterMap(m -> Option.map(d -> ("${c#name}.${m#name}", d), m#doc), c#methods), classes);
    classDocs ::: methodDocs

///
/// The `ClassAnnotation(class, annotation)` facts derived from `classes`.
///
def classAnnotationFacts(classes: List[ClassInfo]): List[(String, String)] =
    List.flatMap(c -> List.map(a -> (c#name, a), c#annotations), classes)

///
/// The `MethodAnnotation(class, method, annotation)` facts derived from
/// `classes`.
///
def methodAnnotationFacts(classes: List[ClassInfo]): List[(String, String, String)] =
    List.flatMap(c -> List.flatMap(m -> List.map(a -> (c#name, m#name, a), m#annotations), c#methods), classes)

///
/// Builds the Datalog program that computes, from `classes`:
///
/// * `Inherits(sub, sup)` -- the transitive closure of `extends`/`implements`,
///   i.e. every class or interface a class derives from, directly or not.
/// * `AvailableMethod(cls, method)` -- every method callable on `cls`,
///   whether declared directly or inherited from an ancestor.
/// * `Doc(name, text)` -- the doc comment for a class/interface (`name`) or
///   method (`Class.method`), if any.
/// * `Domesticated(cls)` -- `cls` (or an ancestor) is annotated
///   `@Domesticated`.
/// * `KnowsTrick(cls)` -- `cls` has (or inherited) a method annotated
///   `@Trick`.
///
/// See: https://doc.flix.dev/fixpoints.html#injecting-facts-into-datalog
///
def classHierarchy(classes: List[ClassInfo]): #{
    Inherits(String, String), AvailableMethod(String, String), Doc(String, String),
    Domesticated(String), KnowsTrick(String) | r
} =
    let cs = inject classFacts(classes) into Class/1;
    let es = inject extendsFacts(classes) into Extends/2;
    let is = inject implementsFacts(classes) into Implements/2;
    let ms = inject declaresMethodFacts(classes) into DeclaresMethod/2;
    let ds = inject docFacts(classes) into Doc/2;
    let cas = inject classAnnotationFacts(classes) into ClassAnnotation/2;
    let mas = inject methodAnnotationFacts(classes) into MethodAnnotation/3;
    let rules = #{
        Inherits(sub, sup) :- Extends(sub, sup).
        Inherits(sub, sup) :- Implements(sub, sup).
        Inherits(sub, sup) :- Extends(sub, mid), Inherits(mid, sup).
        Inherits(sub, sup) :- Implements(sub, mid), Inherits(mid, sup).
        AvailableMethod(cls, m) :- DeclaresMethod(cls, m).
        AvailableMethod(cls, m) :- Inherits(cls, sup), DeclaresMethod(sup, m).
        Domesticated(cls) :- ClassAnnotation(cls, "Domesticated").
        Domesticated(cls) :- Inherits(cls, sup), ClassAnnotation(sup, "Domesticated").
        KnowsTrick(cls) :- MethodAnnotation(cls, m, "Trick").
        KnowsTrick(cls) :- Inherits(cls, sup), MethodAnnotation(sup, m, "Trick").
    };
    solve cs, es, is, ms, ds, cas, mas, rules project Inherits, AvailableMethod, Doc, Domesticated, KnowsTrick

///
/// Demonstrates parsing a Java source module with a fork of OpenRewrite's
/// Java LST parser, extracting facts about its classes, interfaces,
/// `extends`/`implements` relationships, declared methods, and Javadoc
/// comments via plain Java interop, and injecting those facts into a
/// Datalog program that computes the full inheritance chain and the set
/// of methods available on each class (own and inherited).
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

    println("Available methods (own and inherited), with their Javadoc:");
    foreach (c <- classes) {
        let name = c#name;
        let classDoc = query hierarchy select doc from Doc(name, doc);
        println("  ${name}${docSuffix(classDoc)}");
        let methods = query hierarchy select method from AvailableMethod(name, method);
        foreach (m <- methods |> Vector.toList |> List.sort) {
            let key = "${name}.${m}";
            let methodDoc = query hierarchy select doc from Doc(key, doc);
            println("    ${m}${docSuffix(methodDoc)}")
        }
    };
    println("");

    let domesticated = query hierarchy select cls from Domesticated(cls);
    println("Domesticated (own or inherited @Domesticated):");
    domesticated |> Vector.forEach(cls -> println("  ${cls}"));
    println("");

    let knowsTrick = query hierarchy select cls from KnowsTrick(cls);
    println("Knows a trick (own or inherited @Trick method):");
    knowsTrick |> Vector.forEach(cls -> println("  ${cls}"))

///
/// Formats the first result of a `Doc` query as a trailing " -- text"
/// suffix, or the empty string if there was no doc comment.
///
def docSuffix(doc: Vector[String]): String =
    match Vector.head(doc) {
        case Some(text) => " -- ${text}"
        case None       => ""
    }
