import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/// Smoke-tests the three native CLI ports (src/java/Main.java, src/kotlin/Main.kt,
/// src/scala/Main.scala -- see README.md's "Native ports" section) against the same observable
/// behavior as src/flix/Main.flix. Each port is spawned as a subprocess rather than invoked
/// in-process, since the error-path args exercise System.exit()/exitProcess()/sys.exit(), any of
/// which would tear down this test JVM if called directly.
public class PortsCliTest {

    private record Result(String output, int exitCode) {
    }

    private static Result run(String mainClass, String... args) throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("portsClasspath");
        List<String> command = new ArrayList<>(List.of(javaBin, "-cp", classpath, mainClass));
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        return new Result(output, exitCode);
    }

    private static void assertCliBehavior(String mainClass) throws IOException, InterruptedException {
        assertEquals("Hello World!\n", run(mainClass).output());
        assertEquals("Hello, Ada!\n", run(mainClass, "Ada").output());

        for (String helpFlag : List.of("-h", "--help", "--usage")) {
            Result help = run(mainClass, helpFlag);
            assertEquals(0, help.exitCode());
            assertTrue(help.output().startsWith("Usage: flix-lab [-h|--help|--usage] [NAME]"));
        }

        Result error = run(mainClass, "--bogus");
        assertEquals(2, error.exitCode());
        assertTrue(error.output().contains("unrecognized option `--bogus'"));
    }

    @Test
    public void javaPort() throws IOException, InterruptedException {
        assertCliBehavior("Main");
    }

    @Test
    public void kotlinPort() throws IOException, InterruptedException {
        assertCliBehavior("MainKt");
    }

    @Test
    public void scalaPort() throws IOException, InterruptedException {
        assertCliBehavior("run");
    }
}
