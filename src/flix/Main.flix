import dev.wstein.flixlab.{Greeter => Greeter}

use Sys.Env
use Sys.Exit
use Util.GetOpt
use Util.GetOpt.ArgDescr
use Util.GetOpt.ArgOrder
use Util.GetOpt.OptionDescr

///
/// The command line flags recognized by this program.
///
enum Flag with Eq, ToString {
    case Help
}

///
/// The command line option descriptors.
///
def options(): List[OptionDescr[Flag]] =
    {
        optionIds     = 'h' :: Nil,
        optionNames   = "help" :: "usage" :: Nil,
        argDescriptor = ArgDescr.NoArg(Flag.Help),
        explanation   = "Show this help message and exit."
    } :: Nil

///
/// Returns the usage/help text for this program.
///
def usage(): String =
    GetOpt.usageInfo("Usage: flix-lab [-h|--help|--usage] [NAME]", options())

///
/// Returns the greeting for the first non-option argument, if any.
///
def greet(nonOptions: List[String]): String =
    match nonOptions {
        case name :: _ => "Hello, ${name}!"
        case Nil       => "Hello World!"
    }

///
/// Calls plain Java, so a debug session has a Flix -> Java -> Flix path to step through.
/// See src/javalib/dev/wstein/flixlab/Greeter.java.
///
def helloFromJava(): String \ IO =
    Greeter.greeting()

///
/// Calls nothing outside this file, so the classes compiled from it draw on a single source and
/// `Smap.build()` emits no `SourceDebugExtension`. That is the shape row 9 of the native-debugger
/// gate needs: a breakpoint here resolves through the *default* stratum rather than through `Flix`.
///
/// Every other declaration in this file reaches the standard library, so inlining pulls in a second
/// source and SMAP always appears -- which is why this had to be written rather than found. Keep it
/// free of library calls; adding one silently converts it into another row-8 fixture.
///
/// Verify with:
///     javap -v -p 'build/class/Def$noSmapFixture.class' | grep -c SourceDebugExtension   # 0
///
def noSmapFixture(n: Int32): Int32 =
    let doubled = n * 2;
    let shifted = doubled + 1;
    shifted

/// The main entry point.
def main(): Unit \ { IO, Env, Exit } =
    println(helloFromJava());
    println(noSmapFixture(20));
    let args = Env.getArgs();
    match GetOpt.getOpt(ArgOrder.Permute, options(), args) {
        case Validation.Success(r) =>
            if (List.memberOf(Flag.Help, r#options))
                println(usage())
            else
                println(greet(r#nonOptions))
        case Validation.Failure(errs) =>
            Nec.toList(errs) |> List.forEach(println);
            println(usage());
            Exit.exit(2)
    }
