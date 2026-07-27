import java.util.ArrayList;
import java.util.List;

/// Java 21 port of ../../src/Main.flix -- same CLI behavior (help flag, greeting), reimplemented
/// natively instead of calling Flix's Util.GetOpt: -h/--help/--usage prints usage and exits 0,
/// an unrecognized option prints an error plus usage and exits 2, otherwise the first non-option
/// argument (if any) is greeted.
public class Main {

    private static final String USAGE = """
            Usage: flix-lab [-h|--help|--usage] [NAME]
              -h  --help, --usage  Show this help message and exit.""";

    public static void main(String[] args) {
        boolean help = false;
        List<String> nonOptions = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String arg : args) {
            switch (arg) {
                case "-h", "--help", "--usage" -> help = true;
                default -> {
                    if (arg.startsWith("-")) {
                        errors.add("unrecognized option `" + arg + "'");
                    } else {
                        nonOptions.add(arg);
                    }
                }
            }
        }

        if (help) {
            System.out.println(USAGE);
            return;
        }
        if (!errors.isEmpty()) {
            errors.forEach(System.out::println);
            System.out.println(USAGE);
            System.exit(2);
        }

        System.out.println(nonOptions.isEmpty() ? "Hello World!" : "Hello, " + nonOptions.getFirst() + "!");
    }
}
