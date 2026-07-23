///
/// Tests for the sample people used by the family-tree demo, built directly
/// as Flix values (no file IO), plus end-to-end tests that parse the real
/// `resources/people.yaml` resource file.
///
def samplePeople(): List[Person] =
    { name = "Alice", age = 71, gender = Gender.Female, father = None, mother = None } ::
    { name = "Albert", age = 74, gender = Gender.Male, father = None, mother = None } ::
    { name = "Bob", age = 45, gender = Gender.Male, father = Some("Albert"), mother = Some("Alice") } ::
    { name = "Carol", age = 22, gender = Gender.Female, father = Some("Bob"), mother = None } ::
    Nil

@Test
def testPersonFactsIncludesEveryone(): Unit \ Assert =
    Assert.assertEq(
        expected = ("Alice", 71) :: ("Albert", 74) :: ("Bob", 45) :: ("Carol", 22) :: Nil,
        personFacts(samplePeople())
    )

@Test
def testFatherFactsSkipsPeopleWithoutAFather(): Unit \ Assert =
    Assert.assertEq(expected = ("Bob", "Albert") :: ("Carol", "Bob") :: Nil, fatherFacts(samplePeople()))

@Test
def testMotherFactsSkipsPeopleWithoutAMother(): Unit \ Assert =
    Assert.assertEq(expected = ("Bob", "Alice") :: Nil, motherFacts(samplePeople()))

@Test
def testFamilyTreeComputesTransitiveAncestry(): Unit \ Assert =
    let tree = familyTree(samplePeople());
    let result = query tree select (descendant, ancestor) from Ancestor(descendant, ancestor);
    Assert.assertEq(expected = true, Vector.memberOf((("Carol", "Alice")), result))

@Test
def testFamilyTreeOmitsNonAncestorPairs(): Unit \ Assert =
    let tree = familyTree(samplePeople());
    let result = query tree select (descendant, ancestor) from Ancestor(descendant, ancestor);
    Assert.assertEq(expected = false, Vector.memberOf((("Alice", "Carol")), result))

@Test
def testFamilyTreeFatherQueryMatchesFacts(): Unit \ Assert =
    let tree = familyTree(samplePeople());
    let result = query tree select (child, father) from Father(child, father);
    Assert.assertEq(expected = true, Vector.memberOf((("Bob", "Albert")), result))

@Test
def testReadResourceFileLoadsAllPeople(): Unit \ {Assert, IO} =
    let people = toPeople(parseRecords(readLines("resources/people.yaml")));
    Assert.assertEq(expected = 66, List.length(people))

@Test
def testReadResourceFileParsesBothParents(): Unit \ {Assert, IO} =
    let people = toPeople(parseRecords(readLines("resources/people.yaml")));
    match List.find(p -> p#name == "Finn", people) {
        case Some(finn) =>
            Assert.assertEq(expected = 6, finn#age);
            Assert.assertEq(expected = Gender.Male, finn#gender);
            Assert.assertEq(expected = Some("Derek"), finn#father);
            Assert.assertEq(expected = Some("Diana"), finn#mother)
        case None => Assert.assertTrue(false)
    }

@Test
def testReadResourceFileCommonAncestorHasNoParents(): Unit \ {Assert, IO} =
    let people = toPeople(parseRecords(readLines("resources/people.yaml")));
    match List.find(p -> p#name == "Adam", people) {
        case Some(adam) =>
            Assert.assertEq(expected = None, adam#father);
            Assert.assertEq(expected = None, adam#mother)
        case None => Assert.assertTrue(false)
    }

@Test
def testReadResourceFileEveryoneDescendsFromTheCommonAncestors(): Unit \ {Assert, IO} =
    let people = toPeople(parseRecords(readLines("resources/people.yaml")));
    let tree = familyTree(people);
    let result = query tree select (descendant, ancestor) from Ancestor(descendant, ancestor);
    Assert.assertEq(expected = true, Vector.memberOf((("Finn", "Adam")), result));
    Assert.assertEq(expected = true, Vector.memberOf((("Finn", "Eve")), result))
