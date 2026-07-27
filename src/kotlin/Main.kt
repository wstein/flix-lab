import kotlin.system.exitProcess

// Kotlin port of ../../src/Main.flix -- same CLI behavior (help flag, greeting), reimplemented
// natively instead of calling Flix's Util.GetOpt: -h/--help/--usage prints usage and exits 0, an
// unrecognized option prints an error plus usage and exits 2, otherwise the first non-option
// argument (if any) is greeted.

private const val USAGE = """Usage: flix-lab [-h|--help|--usage] [NAME]
  -h  --help, --usage  Show this help message and exit."""

fun main(args: Array<String>) {
    var help = false
    val nonOptions = mutableListOf<String>()
    val errors = mutableListOf<String>()

    for (arg in args) {
        when (arg) {
            "-h", "--help", "--usage" -> help = true
            else -> if (arg.startsWith("-")) {
                errors += "unrecognized option `$arg'"
            } else {
                nonOptions += arg
            }
        }
    }

    if (help) {
        println(USAGE)
        return
    }
    if (errors.isNotEmpty()) {
        errors.forEach(::println)
        println(USAGE)
        exitProcess(2)
    }

    println(nonOptions.firstOrNull()?.let { "Hello, $it!" } ?: "Hello World!")
}
