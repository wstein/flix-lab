import java.nio.file.{Files => Files}
import java.nio.file.{Path => JPath}
import java.util.{ArrayList => ArrayList}
import java.util.{HashMap => HashMap}
import java.util.{List => JList}

///
/// The gender of a person, as recorded in the resource file.
///
pub enum Gender with Eq, ToString {
    case Male
    case Female
}

///
/// Parses the `gender` field ("male" or "female", case-insensitive).
/// Defaults to `Male` if the value is missing or unrecognized.
///
def parseGender(s: Option[String]): Gender =
    match Option.map(String.toLowerCase, s) {
        case Some("female") => Gender.Female
        case _              => Gender.Male
    }

///
/// A person parsed from the YAML-like resource file. `father` and `mother`
/// are `None` for the root of a family tree.
///
pub type alias Person = {
    name   = String,
    age    = Int32,
    gender = Gender,
    father = Option[String],
    mother = Option[String]
}

///
/// Reads all lines of the text file at `path` using plain Java IO
/// (`java.nio.file.Files`), converting the resulting `java.util.List`
/// into a Flix `List` via `Adaptor.fromList`.
///
def readLines(path: String): List[String] \ IO =
    let p = JPath.of(path);
    let lines: JList[String] = Files.readAllLines(p);
    Adaptor.fromList(lines)

///
/// Parses the simplified YAML records (a flat list of `- key: value`
/// blocks, with `#`-prefixed comment lines ignored) into a
/// `java.util.ArrayList` of `java.util.HashMap`s. This demonstrates
/// building and populating Java collection structures directly from Flix.
///
def parseRecords(lines: List[String]): ArrayList[HashMap[String, String]] \ IO =
    let records: ArrayList[HashMap[String, String]] = new ArrayList();
    parseRecordsHelper(lines, records);
    records

def parseRecordsHelper(lines: List[String], records: ArrayList[HashMap[String, String]]): Unit \ IO =
    match lines {
        case Nil => ()
        case line :: rest =>
            let trimmed = String.trim(line);
            if (String.isEmpty(trimmed) or String.startsWith(prefix = "#", trimmed))
                parseRecordsHelper(rest, records)
            else if (String.startsWith(prefix = "- ", trimmed))
                let m: HashMap[String, String] = new HashMap();
                discard records.add(m);
                addKeyValue(m, String.dropLeft(2, trimmed));
                parseRecordsHelper(rest, records)
            else
                let m = records.get(records.size() - 1);
                addKeyValue(m, trimmed);
                parseRecordsHelper(rest, records)
    }

///
/// Splits `kv` on the first `:` and stores the trimmed key/value pair in `m`.
///
def addKeyValue(m: HashMap[String, String], kv: String): Unit \ IO =
    let (k, rest) = String.breakOnLeft(substr = ":", kv);
    let key = String.trim(k);
    let value = String.trim(String.dropLeft(1, rest));
    discard m.put(key, value);
    ()

///
/// Converts the parsed Java records into a Flix `List[Person]`, using
/// `Adaptor.fromList` to drain the `java.util.ArrayList` (upcast to
/// `java.util.List` since `Adaptor.fromList` is defined over the interface).
///
def toPeople(records: ArrayList[HashMap[String, String]]): List[Person] \ IO =
    let asJList: JList[HashMap[String, String]] = checked_cast(records);
    Adaptor.fromList(asJList) |> List.map(toPerson)

///
/// Looks up `key` in `m`, if present.
///
def getOpt(m: HashMap[String, String], key: String): Option[String] \ IO =
    Object.toOption(m.get(key))

def toPerson(m: HashMap[String, String]): Person \ IO =
    let name = m.get("name");
    let age = getOpt(m, "age") |> Option.flatMap(Int32.fromString) |> Option.getWithDefault(0);
    let gender = parseGender(getOpt(m, "gender"));
    let father = getOpt(m, "father");
    let mother = getOpt(m, "mother");
    { name = name, age = age, gender = gender, father = father, mother = mother }

///
/// The `Father(child, father)` facts derived from `people`.
///
def fatherFacts(people: List[Person]): List[(String, String)] =
    List.filterMap(p -> Option.map(father -> (p#name, father), p#father), people)

///
/// The `Mother(child, mother)` facts derived from `people`.
///
def motherFacts(people: List[Person]): List[(String, String)] =
    List.filterMap(p -> Option.map(mother -> (p#name, mother), p#mother), people)

///
/// Builds the Datalog program that computes, from `people`:
///
/// * `Father(child, father)` / `Mother(child, mother)` -- the two parents
///   of a person, taken directly from the resource file.
/// * `Grandfather(grandchild, gf)` / `Grandmother(grandchild, gm)` -- the
///   grandparents of a person, found by joining `Father`/`Mother` one level
///   up through either parent.
/// * `Ancestor(descendant, ancestor)` -- the transitive closure of "has a
///   parent" over both `Father` and `Mother`, i.e. every ancestor above a
///   person, not just their immediate parents.
///
/// See: https://doc.flix.dev/fixpoints.html#injecting-facts-into-datalog
///
def familyTree(people: List[Person]): #{
    Ancestor(String, String), Father(String, String), Mother(String, String),
    Grandfather(String, String), Grandmother(String, String) | r
} =
    let fs = inject fatherFacts(people) into Father/2;
    let ms = inject motherFacts(people) into Mother/2;
    let rules = #{
        ParentOf(child, parent) :- Father(child, parent).
        ParentOf(child, parent) :- Mother(child, parent).
        Ancestor(descendant, ancestor) :- ParentOf(descendant, ancestor).
        Ancestor(descendant, ancestor) :- ParentOf(descendant, mid), Ancestor(mid, ancestor).
        Grandfather(grandchild, gf) :- ParentOf(grandchild, parent), Father(parent, gf).
        Grandmother(grandchild, gm) :- ParentOf(grandchild, parent), Mother(parent, gm).
    };
    solve fs, ms, rules project Ancestor, Father, Mother, Grandfather, Grandmother

///
/// Returns the direct children of `parent` (matched by name) in `people`.
///
def childrenOf(people: List[Person], parent: String): List[Person] =
    List.filter(p -> p#father == Some(parent) or p#mother == Some(parent), people)

///
/// Returns the name of `parent`'s co-parent, given `parent`'s children (as
/// returned by `childrenOf`), i.e. the other parent of any one of them.
///
def partnerFromChildren(children: List[Person], parent: String): Option[String] =
    match children {
        case child :: _ =>
            if (child#father == Some(parent)) child#mother else child#father
        case Nil => None
    }

///
/// Returns the name of `parent`'s co-parent, i.e. the other parent of any
/// one of their children, if they have children.
///
def partnerOf(people: List[Person], parent: String): Option[String] =
    partnerFromChildren(childrenOf(people, parent), parent)

///
/// The parent names (father's, mother's, or both) that `p` should be
/// indexed under in a `childrenIndex`.
///
def parentLinks(p: Person): List[(String, Person)] =
    (Option.map(father -> (father, p), p#father) |> Option.toList) :::
        (Option.map(mother -> (mother, p), p#mother) |> Option.toList)

///
/// Builds a lookup from parent name to that parent's children, in a single
/// pass over `people`. This is the blueprint for turning `childrenOf`'s
/// per-call `O(n)` scan (`O(n^2)` total across a whole tree dump) into an
/// `O(n)` index built once, followed by `O(1)`-ish lookups per node.
///
def childrenIndex(people: List[Person]): Map[String, List[Person]] =
    people
        |> List.flatMap(parentLinks)
        |> List.foldLeft((index, pair) -> match pair {
            case (parent, child) => Map.insertWith(List.append, parent, child :: Nil, index)
        }, Map.empty())

///
/// Prints the descendants of `name` as an indented tree, pairing each
/// person with their partner (the other parent of their children), if any.
/// This walks the precomputed `index` with plain Flix recursion -- it is
/// not a Datalog query, just a display built from the same parsed facts.
///
def printSubtree(index: Map[String, List[Person]], name: String, depth: Int32): Unit \ IO =
    let indent = String.repeat(depth * 2, " ");
    let kids = Map.getWithDefault(name, Nil, index);
    let label = match partnerFromChildren(kids, name) {
        case Some(partner) => "${name} & ${partner}"
        case None          => name
    };
    println("${indent}${label}");
    kids
        |> List.sortBy(p -> p#name)
        |> List.forEach(child -> printSubtree(index, child#name, depth + 1))

///
/// Dumps the full family tree, starting from the shared common ancestor
/// `root` (e.g. `"Adam"`), down through every descendant. Builds the
/// parent -> children index once, up front, rather than re-scanning
/// `people` for every node visited during the recursive dump.
///
def dumpTree(people: List[Person], root: String): Unit \ IO =
    printSubtree(childrenIndex(people), root, 0)

///
/// Demonstrates reading a YAML-like file via pure Java IO, building
/// `java.util.ArrayList`/`java.util.HashMap` structures from it, converting
/// those into Flix facts, and injecting them into a Datalog program that
/// computes the full ancestry, and the fathers/mothers, of every person in
/// the family tree.
///
pub def demo(): Unit \ IO =
    let lines = readLines("resources/people.yaml");
    let records = parseRecords(lines);
    let people = toPeople(records);
    println("Loaded ${List.length(people)} people from resources/people.yaml");
    println("");
    let tree = familyTree(people);

    let ancestry = query tree select (descendant, ancestor) from Ancestor(descendant, ancestor);
    println("Full ancestry (descendant -> ancestor):");
    ancestry |> Vector.forEach(match (d, a) -> println("  ${d} -> ${a}"));
    println("");

    let fathers = query tree select (child, father) from Father(child, father);
    println("Fathers (child -> father):");
    fathers |> Vector.forEach(match (c, f) -> println("  ${c} -> ${f}"));
    println("");

    let mothers = query tree select (child, mother) from Mother(child, mother);
    println("Mothers (child -> mother):");
    mothers |> Vector.forEach(match (c, mo) -> println("  ${c} -> ${mo}"));
    println("");

    let grandfathers = query tree select (grandchild, gf) from Grandfather(grandchild, gf);
    println("Grandfathers (grandchild -> grandfather):");
    grandfathers |> Vector.forEach(match (c, gf) -> println("  ${c} -> ${gf}"));
    println("");

    let grandmothers = query tree select (grandchild, gm) from Grandmother(grandchild, gm);
    println("Grandmothers (grandchild -> grandmother):");
    grandmothers |> Vector.forEach(match (c, gm) -> println("  ${c} -> ${gm}"));
    println("");

    println("Full tree (from the common ancestors):");
    dumpTree(people, "Adam")
