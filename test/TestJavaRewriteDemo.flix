///
/// Tests for the OpenRewrite-LST-to-Datalog demo, built directly as Flix
/// values (no parsing), plus end-to-end tests that parse the real
/// `resources/rewrite-sample/Animals.java` source file.
///
def sampleClasses(): List[ClassInfo] =
    { name = "Greeter", extends = None, implements = Nil, methods = "greet" :: Nil } ::
    { name = "Animal", extends = None, implements = Nil, methods = "eat" :: Nil } ::
    { name = "Dog", extends = Some("Animal"), implements = "Greeter" :: Nil, methods = "bark" :: "greet" :: Nil } ::
    { name = "Puppy", extends = Some("Dog"), implements = Nil, methods = "play" :: Nil } ::
    Nil

@Test
def testClassFactsIncludesEveryClass(): Unit \ Assert =
    Assert.assertEq(
        expected = "Greeter" :: "Animal" :: "Dog" :: "Puppy" :: Nil,
        classFacts(sampleClasses())
    )

@Test
def testExtendsFactsSkipsClassesWithoutASuperclass(): Unit \ Assert =
    Assert.assertEq(expected = ("Dog", "Animal") :: ("Puppy", "Dog") :: Nil, extendsFacts(sampleClasses()))

@Test
def testImplementsFactsFlattensEachClassesInterfaces(): Unit \ Assert =
    Assert.assertEq(expected = ("Dog", "Greeter") :: Nil, implementsFacts(sampleClasses()))

@Test
def testDeclaresMethodFactsFlattensEachClassesMethods(): Unit \ Assert =
    Assert.assertEq(
        expected = ("Greeter", "greet") :: ("Animal", "eat") :: ("Dog", "bark") :: ("Dog", "greet") :: ("Puppy", "play") :: Nil,
        declaresMethodFacts(sampleClasses())
    )

@Test
def testClassHierarchyInheritsThroughExtendsAndImplements(): Unit \ Assert =
    let hierarchy = classHierarchy(sampleClasses());
    let result = query hierarchy select (sub, sup) from Inherits(sub, sup);
    Assert.assertEq(expected = true, Vector.memberOf((("Puppy", "Animal")), result));
    Assert.assertEq(expected = true, Vector.memberOf((("Puppy", "Greeter")), result))

@Test
def testClassHierarchyAvailableMethodIncludesInheritedMethods(): Unit \ Assert =
    let hierarchy = classHierarchy(sampleClasses());
    let result = query hierarchy select method from AvailableMethod("Puppy", method);
    let methods = Vector.toList(result);
    Assert.assertTrue(List.memberOf("play", methods));
    Assert.assertTrue(List.memberOf("bark", methods));
    Assert.assertTrue(List.memberOf("eat", methods));
    Assert.assertTrue(List.memberOf("greet", methods))

@Test
def testReadSampleFileParsesAllFourDeclarations(): Unit \ {Assert, IO} =
    let src = readSource("resources/rewrite-sample/Animals.java");
    let classes = allClassInfo(parseJava(src));
    Assert.assertEq(expected = 4, List.length(classes))

@Test
def testReadSampleFileDogExtendsAnimalAndImplementsGreeter(): Unit \ {Assert, IO} =
    let src = readSource("resources/rewrite-sample/Animals.java");
    let classes = allClassInfo(parseJava(src));
    match List.find(c -> c#name == "Dog", classes) {
        case Some(dog) =>
            Assert.assertEq(expected = Some("Animal"), dog#extends);
            Assert.assertEq(expected = "Greeter" :: Nil, dog#implements)
        case None => Assert.assertTrue(false)
    }
