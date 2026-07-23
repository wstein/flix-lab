use Util.GetOpt
use Util.GetOpt.ArgOrder

@Test
def test01(): Unit \ Assert = Assert.assertEq(expected = 2, 1 + 1)

@Test
def testGreetWithName(): Unit \ Assert =
    Assert.assertEq(expected = "Hello, Ada!", greet("Ada" :: Nil))

@Test
def testGreetWithExtraArgs(): Unit \ Assert =
    Assert.assertEq(expected = "Hello, Ada!", greet("Ada" :: "Bob" :: Nil))

@Test
def testGreetWithoutName(): Unit \ Assert =
    Assert.assertEq(expected = "Hello World!", greet(Nil))

@Test
def testShortHelpFlagRecognized(): Unit \ Assert =
    match GetOpt.getOpt(ArgOrder.Permute, options(), "-h" :: Nil) {
        case Validation.Success(r) => Assert.assertEq(expected = Flag.Help :: Nil, r#options)
        case Validation.Failure(_) => Assert.assertTrue(false)
    }

@Test
def testLongHelpFlagRecognized(): Unit \ Assert =
    match GetOpt.getOpt(ArgOrder.Permute, options(), "--help" :: Nil) {
        case Validation.Success(r) => Assert.assertEq(expected = Flag.Help :: Nil, r#options)
        case Validation.Failure(_) => Assert.assertTrue(false)
    }

@Test
def testUsageFlagRecognized(): Unit \ Assert =
    match GetOpt.getOpt(ArgOrder.Permute, options(), "--usage" :: Nil) {
        case Validation.Success(r) => Assert.assertEq(expected = Flag.Help :: Nil, r#options)
        case Validation.Failure(_) => Assert.assertTrue(false)
    }

@Test
def testNameArgumentIsNonOption(): Unit \ Assert =
    match GetOpt.getOpt(ArgOrder.Permute, options(), "Ada" :: Nil) {
        case Validation.Success(r) => Assert.assertEq(expected = "Ada" :: Nil, r#nonOptions)
        case Validation.Failure(_) => Assert.assertTrue(false)
    }

@Test
def testUnknownOptionIsFailure(): Unit \ Assert =
    match GetOpt.getOpt(ArgOrder.Permute, options(), "--bogus" :: Nil) {
        case Validation.Success(_) => Assert.assertTrue(false)
        case Validation.Failure(_) => Assert.assertTrue(true)
    }

@Test
def testUsageContainsAllFlagNames(): Unit \ Assert =
    let text = usage();
    Assert.assertTrue(String.contains(substr = "-h", text));
    Assert.assertTrue(String.contains(substr = "--help", text));
    Assert.assertTrue(String.contains(substr = "--usage", text))
