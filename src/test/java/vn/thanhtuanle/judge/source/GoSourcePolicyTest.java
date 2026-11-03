package vn.thanhtuanle.judge.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GoSourcePolicyTest {

    private static final String FAST_IO = """
            package main

            import (
                "bufio"
                "fmt"
                "math/big"
                "os"
                "sort"
            )

            func main() {
                in := bufio.NewReader(os.Stdin)
                out := bufio.NewWriter(os.Stdout)
                defer out.Flush()
                var n int
                fmt.Fscan(in, &n)
                a := make([]int, n)
                sort.Ints(a)
                fmt.Fprintln(out, big.NewInt(1), "net/http os.StartProcess") // syscall in a comment
                if n < 0 { os.Exit(1) }
            }
            """;

    private static boolean rejected(String source) {
        return SourcePolicies.check("go", source).isPresent();
    }

    @Test
    void anOrdinarySolutionPasses() {
        assertThat(SourcePolicies.check("go", FAST_IO)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "package main\nimport \"syscall\"\nfunc main() { syscall.Kill(-1, 9) }\n",
            "package main\nimport \"unsafe\"\nfunc main() { _ = unsafe.Sizeof(0) }\n",
            "package main\nimport \"net\"\nfunc main() { net.Dial(\"tcp\", \"x:1\") }\n",
            "package main\nimport \"os/exec\"\nfunc main() { exec.Command(\"sh\").Run() }\n",
            "package main\nimport \"plugin\"\nfunc main() { plugin.Open(\"x\") }\n",
            "package main\nimport \"C\"\nfunc main() {}\n",
            "package main\nimport \"reflect\"\nfunc main() { _ = reflect.TypeOf(0) }\n",
            "package main\nimport \"path/filepath\"\nfunc main() { filepath.Glob(\"/*\") }\n",
            "package main\nimport \"github.com/x/y\"\nfunc main() {}\n",
    })
    void aPackageOutsideTheAllowlistIsRejected(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @Test
    void anImportInsideAGroupIsSeen() {
        assertThat(rejected("package main\nimport (\n\t\"fmt\"\n\tx \"syscall\"\n)\nfunc main() { fmt.Println(x.Getpid()) }\n"))
                .isTrue();
    }

    @Test
    void anEscapedImportPathDoesNotHideThePackage() {
        assertThat(rejected("package main\nimport \"sysc\\x61ll\"\nfunc main() {}\n")).isTrue();
    }

    @Test
    void aRawStringImportPathIsSeen() {
        assertThat(rejected("package main\nimport `syscall`\nfunc main() {}\n")).isTrue();
    }

    @Test
    void aCommentBetweenImportAndPathDoesNotHideIt() {
        assertThat(rejected("package main\nimport /* x */ \"syscall\"\nfunc main() {}\n")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "package main\nimport \"os\"\nfunc main() { p, _ := os.FindProcess(1); p.Kill() }\n",
            "package main\nimport \"os\"\nfunc main() { os.StartProcess(\"/bin/sh\", nil, nil) }\n",
            "package main\nimport \"os\"\nfunc main() { os.Open(\"/etc/passwd\") }\n",
            "package main\nimport \"os\"\nfunc main() { os.Remove(\"x\") }\n",
            "package main\nimport \"os\"\nvar f = os . OpenFile\nfunc main() {}\n",
            "package main\nimport \"os\"\nfunc main() { os.\n\tOpen(\"/x\") }\n",
            "package main\nimport \"io/ioutil\"\nfunc main() { ioutil.ReadFile(\"/etc/passwd\") }\n",
    })
    void onlyTheStandardStreamsOfOsAndIoutilAreAllowed(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @Test
    void readingStandardInputWithIoutilPasses() {
        assertThat(SourcePolicies.check("go",
                "package main\nimport (\n\"io/ioutil\"\n\"os\"\n)\nfunc main() { b, _ := ioutil.ReadAll(os.Stdin); _ = b }\n"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "package main\nimport o \"os\"\nfunc main() { o.Open(\"x\") }\n",
            "package main\nimport . \"os\"\nfunc main() { Open(\"x\") }\n",
            "package main\nimport ( o \"io/ioutil\" )\nfunc main() { o.ReadFile(\"x\") }\n",
    })
    void aRestrictedPackageCannotBeRenamed(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "package main\n//go:linkname k syscall.Kill\nfunc k(int, int) error\nfunc main() {}\n",
            "package main\n//go:cgo_import_dynamic x x \"x.so\"\nfunc main() {}\n",
    })
    void aCompilerDirectiveIsRejected(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @Test
    void anUnterminatedStringOrCommentFailsClosed() {
        assertThat(rejected("package main\nfunc main() { x := \"abc\n}\n")).isTrue();
        assertThat(rejected("package main\n/* never closed\nimport \"syscall\"\n")).isTrue();
    }

    @Test
    void quotesInsideARuneDoNotDesynchronizeTheLexer() {
        assertThat(rejected("package main\nvar q = '\"'\nimport \"syscall\"\nfunc main() {}\n")).isTrue();
        assertThat(rejected("package main\nvar q = '\\''\nimport \"syscall\"\nfunc main() {}\n")).isTrue();
    }

    @Test
    void theViolationNamesThePackageAndItsLine() {
        assertThat(SourcePolicies.check("go", "package main\n\nimport \"syscall\"\nfunc main() {}\n"))
                .hasValueSatisfying(v -> {
                    assertThat(v.line()).isEqualTo(3);
                    assertThat(v.message()).startsWith("Line 3: ").contains("syscall");
                });
    }
}
