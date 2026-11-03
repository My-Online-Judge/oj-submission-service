package vn.thanhtuanle.judge.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourcePolicyTest {

    private static final String FAST_IO = """
            import java.io.*;
            import java.math.BigInteger;
            import java.util.*;
            import java.util.stream.*;

            public class Main {
                // Runtime.getRuntime().exec is mentioned here, and java.net too
                static final String NOTE = "ProcessBuilder and /bin/sh in a string";
                public static void main(String[] args) throws Exception {
                    DataInputStream in = new DataInputStream(new FileInputStream(FileDescriptor.in));
                    PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)));
                    Thread t = new Thread(null, () -> out.println(1), "main", 1L << 26);
                    t.start();
                    t.join();
                    long free = Runtime.getRuntime().freeMemory();
                    out.println(java.nio.charset.StandardCharsets.UTF_8);
                    out.flush();
                    if (System.getProperty("ONLINE_JUDGE") == null) System.exit(0);
                }
            }
            """;

    private static boolean rejected(String source) {
        return SourcePolicies.check("java", source).isPresent();
    }

    private static String inMain(String statement) {
        return "public class Main { public static void main(String[] a) throws Exception { " + statement + " } }\n";
    }

    @Test
    void anOrdinarySolutionPasses() {
        assertThat(SourcePolicies.check("java", FAST_IO)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Runtime.getRuntime().exec(\"sh\");",
            "Runtime r = Runtime.getRuntime(); r.exec(\"sh\");",
            "new ProcessBuilder(\"sh\").start();",
            "new java.net.Socket(\"x\", 1);",
            "java . net . Socket s = null;",
            "System.setSecurityManager(null);",
            "System.loadLibrary(\"x\");",
            "System.load(\"/x.so\");",
            "Class.forName(\"java.lang.Runtime\");",
            "Object o = sun.misc.Unsafe.class;",
            "java.lang.reflect.Method m = null;",
            "String.class.getDeclaredField(\"value\").setAccessible(true);",
            "java.lang.invoke.MethodHandles.lookup();",
            "java.nio.file.Files.readAllLines(null);",
            "new javax.script.ScriptEngineManager();",
            "ClassLoader c = null;",
            "System.getenv();",
    })
    void aDangerousCallIsRejected(String statement) {
        assertThat(rejected(inMain(statement))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import java.net.*;\npublic class Main {}\n",
            "import java . net . Socket;\npublic class Main {}\n",
            "import java.lang.reflect.*;\npublic class Main {}\n",
            "import static java.lang.Runtime.getRuntime;\npublic class Main { void f() throws Exception { getRuntime().exec(\"x\"); } }\n",
    })
    void aDangerousImportIsRejected(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @Test
    void unicodeEscapesDoNotHideACall() {
        assertThat(rejected(inMain("new \\u0050rocessBuilder(\"sh\").start();"))).isTrue();
        assertThat(rejected(inMain("new \\uuuu0050rocessBuilder(\"sh\").start();"))).isTrue();
    }

    @Test
    void aUnicodeNewlineEndsALineComment() {
        assertThat(rejected(inMain("// hidden \\u000a new ProcessBuilder(\"sh\").start();"))).isTrue();
    }

    @Test
    void anEscapedBackslashIsNotAUnicodeEscape() {
        assertThat(SourcePolicies.check("java", inMain("String s = \"\\\\u0050rocessBuilder\";"))).isEmpty();
    }
}
