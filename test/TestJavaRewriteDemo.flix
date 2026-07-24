///
/// Tests for the OpenRewrite-LST-to-Datalog demo, built directly as Flix
/// values (no parsing), plus end-to-end tests that parse the real
/// `resources/rewrite-sample/Animals.java` source file.
///
def sampleClasses(): List[ClassInfo] =
    { name = "Greeter", doc = Some("Something that can greet."), extends = None, implements = Nil, annotations = Nil,
      methods = { name = "greet", doc = Some("Says hello."), annotations = Nil, params = Nil, returnDoc = None } :: Nil } ::
    { name = "Animal", doc = None, extends = None, implements = Nil, annotations = Nil,
      methods = { name = "eat", doc = None, annotations = Nil, params = Nil, returnDoc = None } :: Nil } ::
    { name = "Dog", doc = None, extends = Some("Animal"), implements = "Greeter" :: Nil, annotations = "Domesticated" :: Nil,
      methods = { name = "bark", doc = None, annotations = "Trick" :: Nil, params = Nil, returnDoc = None } ::
                { name = "greet", doc = None, annotations = Nil, params = Nil, returnDoc = None } :: Nil } ::
    { name = "Puppy", doc = None, extends = Some("Dog"), implements = Nil, annotations = Nil,
      methods = { name = "play", doc = None, annotations = Nil, params = Nil, returnDoc = None } :: Nil } ::
    Nil

///
/// A single class with one documented, parameterized method, used to test
/// `paramDocFacts`/`returnDocFacts` without disturbing `sampleClasses`'s
/// other expectations.
///
def sampleClassesWithParamDocs(): List[ClassInfo] =
    { name = "Animal", doc = None, extends = None, implements = Nil, annotations = Nil,
      methods = { name = "isHungrierThan", doc = Some("Determines whether this animal is hungrier than another."),
                  annotations = Nil,
                  params = ("other", "the animal to compare against") :: Nil,
                  returnDoc = Some("true if this animal is hungrier than other") } :: Nil } ::
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
def testDocFactsIncludesClassAndMethodDocs(): Unit \ Assert =
    Assert.assertEq(
        expected = ("Greeter", "Something that can greet.") :: ("Greeter.greet", "Says hello.") :: Nil,
        docFacts(sampleClasses())
    )

@Test
def testClassAnnotationFactsFlattensEachClassesAnnotations(): Unit \ Assert =
    Assert.assertEq(expected = ("Dog", "Domesticated") :: Nil, classAnnotationFacts(sampleClasses()))

@Test
def testMethodAnnotationFactsFlattensEachMethodsAnnotations(): Unit \ Assert =
    Assert.assertEq(expected = ("Dog", "bark", "Trick") :: Nil, methodAnnotationFacts(sampleClasses()))

@Test
def testParamDocFactsFlattensEachMethodsParams(): Unit \ Assert =
    Assert.assertEq(
        expected = ("Animal", "isHungrierThan", "other", "the animal to compare against") :: Nil,
        paramDocFacts(sampleClassesWithParamDocs())
    )

@Test
def testParamDocFactsIsEmptyWithoutParams(): Unit \ Assert =
    Assert.assertEq(expected = Nil, paramDocFacts(sampleClasses()))

@Test
def testReturnDocFactsSkipsMethodsWithoutAReturnTag(): Unit \ Assert =
    Assert.assertEq(
        expected = ("Animal", "isHungrierThan", "true if this animal is hungrier than other") :: Nil,
        returnDocFacts(sampleClassesWithParamDocs())
    )

@Test
def testReturnDocFactsIsEmptyWithoutAReturnTag(): Unit \ Assert =
    Assert.assertEq(expected = Nil, returnDocFacts(sampleClasses()))

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
def testClassHierarchyParamDocQueryMatchesFacts(): Unit \ Assert =
    let hierarchy = classHierarchy(sampleClassesWithParamDocs());
    let result = query hierarchy select (param, desc) from ParamDoc("Animal", "isHungrierThan", param, desc);
    Assert.assertEq(expected = true, Vector.memberOf((("other", "the animal to compare against")), result))

@Test
def testClassHierarchyReturnDocQueryMatchesFacts(): Unit \ Assert =
    let hierarchy = classHierarchy(sampleClassesWithParamDocs());
    let result = query hierarchy select desc from ReturnDoc("Animal", "isHungrierThan", desc);
    Assert.assertEq(expected = true, Vector.memberOf("true if this animal is hungrier than other", result))

@Test
def testClassHierarchyDomesticatedPropagatesThroughInheritance(): Unit \ Assert =
    let hierarchy = classHierarchy(sampleClasses());
    let result = query hierarchy select cls from Domesticated(cls);
    let classes = Vector.toList(result);
    Assert.assertTrue(List.memberOf("Dog", classes));
    Assert.assertTrue(List.memberOf("Puppy", classes));
    Assert.assertTrue(not List.memberOf("Animal", classes))

@Test
def testClassHierarchyKnowsTrickPropagatesThroughInheritance(): Unit \ Assert =
    let hierarchy = classHierarchy(sampleClasses());
    let result = query hierarchy select cls from KnowsTrick(cls);
    let classes = Vector.toList(result);
    Assert.assertTrue(List.memberOf("Dog", classes));
    Assert.assertTrue(List.memberOf("Puppy", classes));
    Assert.assertTrue(not List.memberOf("Animal", classes))

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
            Assert.assertEq(expected = "Greeter" :: Nil, dog#implements);
            Assert.assertEq(expected = "Domesticated" :: Nil, dog#annotations)
        case None => Assert.assertTrue(false)
    }

@Test
def testReadSampleFileParsesDocAndTrickAnnotation(): Unit \ {Assert, IO} =
    let src = readSource("resources/rewrite-sample/Animals.java");
    let classes = allClassInfo(parseJava(src));
    match List.find(c -> c#name == "Dog", classes) {
        case Some(dog) =>
            Assert.assertEq(expected = Some("A domesticated canine."), dog#doc);
            match List.find(m -> m#name == "bark", dog#methods) {
                case Some(bark) =>
                    Assert.assertEq(expected = Some("Makes a barking sound."), bark#doc);
                    Assert.assertEq(expected = "Trick" :: Nil, bark#annotations)
                case None => Assert.assertTrue(false)
            }
        case None => Assert.assertTrue(false)
    }

@Test
def testReadSampleFileParsesParamAndReturnTags(): Unit \ {Assert, IO} =
    let src = readSource("resources/rewrite-sample/Animals.java");
    let classes = allClassInfo(parseJava(src));
    match List.find(c -> c#name == "Animal", classes) {
        case Some(animal) =>
            match List.find(m -> m#name == "isHungrierThan", animal#methods) {
                case Some(m) =>
                    Assert.assertEq(expected = ("other", "the animal to compare against") :: Nil, m#params);
                    Assert.assertEq(expected = Some("true if this animal is hungrier than the other animal"), m#returnDoc)
                case None => Assert.assertTrue(false)
            }
        case None => Assert.assertTrue(false)
    }
