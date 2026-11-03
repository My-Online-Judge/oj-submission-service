package vn.thanhtuanle.judge.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CFamilySourcePolicyTest {

    private static final String TEMPLATE = """
            #pragma GCC optimize("O3,unroll-loops")
            #pragma GCC target("avx2")
            #include <bits/stdc++.h>
            #include "bits/stdc++.h"
            #include <unistd.h>
            #include <sys/mman.h>
            #define rep(i, n) for (int i = 0; i < (n); ++i)
            #define CAT(a, b) a##b
            using namespace std;
            const long long MOD = 1'000'000'007; // system("rm -rf /") in a comment
            struct Treap { Treap* clone(); void link(int); void connect(int); };
            int parent[100];
            int main() {
            #ifndef ONLINE_JUDGE
                freopen("input.txt", "r", stdin);
            #endif
                ios::sync_with_stdio(false);
                string s = "fork() and #include </etc/passwd> in a string";
                char q = '"';
                cout << s << q << '\\n';
                return 0;
            }
            """;

    private static boolean rejected(String language, String source) {
        return SourcePolicies.check(language, source).isPresent();
    }

    @Test
    void aCompetitiveTemplatePassesInBothLanguages() {
        assertThat(SourcePolicies.check("cpp", TEMPLATE)).isEmpty();
        assertThat(SourcePolicies.check("c", TEMPLATE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "#include </etc/passwd>\n",
            "#include \"/proc/self/environ\"\n",
            "#include \"../secret.h\"\n",
            "#include </dev/zero>\n",
            "  #  include   </etc/passwd>\n",
            "#/* x */include </etc/passwd>\n",
            "%:include </etc/passwd>\n",
            "??=include </etc/passwd>\n",
            "#inc\\\nlude </etc/passwd>\n",
            "#include_next <stdio.h>\n",
            "#import </etc/passwd>\n",
            "#define F </etc/passwd>\n#include F\n",
    })
    void anIncludeThatReachesOutsideTheSystemHeadersIsRejected(String source) {
        assertThat(rejected("cpp", source)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "#include <sys/socket.h>\n",
            "#include <netinet/in.h>\n",
            "#include <arpa/inet.h>\n",
            "#include <sys/ptrace.h>\n",
            "#include <sys/syscall.h>\n",
            "#include <linux/seccomp.h>\n",
            "#include <dlfcn.h>\n",
    })
    void aSystemHeaderThatOnlyServesAttacksIsRejected(String source) {
        assertThat(rejected("c", source)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "int main() { asm(\"syscall\"); }\n",
            "int main() { __asm__(\"int $0x80\"); }\n",
            "int main() { __asm (\"nop\"); }\n",
            "__asm__(\".incbin \\\"/etc/passwd\\\"\");\n",
            "#define S(x) #x\nconst char* p = S(.incbin);\n",
            "_Pragma(\"GCC dependency \\\"/etc/passwd\\\"\")\n",
            "#pragma GCC dependency \"/etc/passwd\"\n",
    })
    void assemblyAndCompileTimeFileAccessAreRejected(String source) {
        assertThat(rejected("cpp", source)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "int main() { system(\"sh\"); }\n",
            "int main() { std::system(\"sh\"); }\n",
            "int main() { popen(\"sh\", \"r\"); }\n",
            "int main() { fork(); }\n",
            "int main() { execve(\"/bin/sh\", 0, 0); }\n",
            "int main() { syscall(62, -1, 9); }\n",
            "int main() { socket(2, 1, 0); }\n",
            "int main() { sys\\\ntem(\"sh\"); }\n",
    })
    void aProcessOrNetworkCallIsRejected(String source) {
        assertThat(rejected("cpp", source)).isTrue();
    }

    @Test
    void aMemberNamedLikeACallStaysAllowed() {
        assertThat(SourcePolicies.check("cpp", "int main() { o.system(1); p->fork(); }\n")).isEmpty();
    }

    @Test
    void aRawStringDoesNotHideOrInventCode() {
        assertThat(SourcePolicies.check("cpp", "const char* s = R\"x(system(\"a\") )\" )x\";\nint main() {}\n")).isEmpty();
        assertThat(rejected("cpp", "const char* s = R\"(a)\"; int main() { system(\"a\"); }\n")).isTrue();
    }

    @Test
    void aSplicedLineKeepsItsLineNumber() {
        assertThat(SourcePolicies.check("cpp", "#define A 1 \\\n  + 1\nint x;\nint main() { fork(); }\n"))
                .hasValueSatisfying(v -> assertThat(v.line()).isEqualTo(4));
    }
}
