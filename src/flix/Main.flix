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

/// The main entry point.
def main(): Unit \ { IO, Env, Exit } =
    println(helloFromJava());
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
