// Scala 3 port of ../../src/Main.flix -- same CLI behavior (help flag, greeting), reimplemented
// natively instead of calling Flix's Util.GetOpt: -h/--help/--usage prints usage and exits 0, an
// unrecognized option prints an error plus usage and exits 2, otherwise the first non-option
// argument (if any) is greeted.

val Usage =
  """Usage: flix-lab [-h|--help|--usage] [NAME]
    |  -h  --help, --usage  Show this help message and exit.""".stripMargin

@main def run(args: String*): Unit =
  val (help, nonOptions, errors) = args.foldLeft((false, Vector.empty[String], Vector.empty[String])) {
    case ((help, nonOpts, errs), arg) =>
      arg match
        case "-h" | "--help" | "--usage" => (true, nonOpts, errs)
        case a if a.startsWith("-")      => (help, nonOpts, errs :+ s"unrecognized option `$a'")
        case a                           => (help, nonOpts :+ a, errs)
  }

  if help then println(Usage)
  else if errors.nonEmpty then
    errors.foreach(println)
    println(Usage)
    sys.exit(2)
  else
    println(nonOptions.headOption.map(n => s"Hello, $n!").getOrElse("Hello World!"))
