package dev.wstein.flixlab

import groovy.io.FileType
import spock.lang.Specification

import java.util.jar.JarFile

class PackagedInteropSpec extends Specification {

    private final File projectRoot = new File(System.getProperty("flixLabRoot"))

    def "packaged program calls exported Flix from Java and runs the Clojure greeter"() {
        given:
        List<File> jars = [new File(projectRoot, "artifact/flix-lab.jar")]
        new File(projectRoot, "lib").eachFileRecurse(FileType.FILES) { File file ->
            if (file.name.endsWith(".jar")) jars.add(file)
        }
        String classpath = jars*.absolutePath.join(File.pathSeparator)
        String java = new File(System.getProperty("java.home"), "bin/java").absolutePath

        when:
        Process process = new ProcessBuilder(java, "-cp", classpath, "Main")
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
        String output = new String(process.inputStream.readAllBytes())
        int exitCode = process.waitFor()
        List<String> lines = output.readLines()

        then:
        exitCode == 0
        lines[0] == "Hello Java via Flix!"
        lines.contains("Hello Clojure!")
        lines.last() == "Hello World!"
    }

    def "Java jar excludes the compile-only Flix facade"() {
        given:
        JarFile javaJar = new JarFile(new File(projectRoot, "vendor/javalib/flixlab-javalib.jar"))

        expect:
        javaJar.getEntry("dev/wstein/flixlab/Greeter.class") != null
        javaJar.getEntry("dev/flix/gen/JavaGreeting.class") == null

        cleanup:
        javaJar.close()
    }
}
