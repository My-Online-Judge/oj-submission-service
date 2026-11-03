package vn.thanhtuanle.judge.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PythonSourcePolicyTest {

    private static final String FAST_IO = """
            import os, sys, io
            import threading
            from collections import defaultdict, deque
            from functools import lru_cache
            from math import gcd
            import re
            # os.system('rm -rf /') in a comment
            NOTE = "import subprocess in a string, __import__ too"
            DOC = '''
            import socket
            '''
            input = io.BytesIO(os.read(0, os.fstat(0).st_size)).readline
            sys.setrecursionlimit(1 << 25)
            threading.stack_size(1 << 26)
            data = open(0).read().split()
            raw = open(0, 'rb').read()
            pattern = re.compile(r'\\d+')
            print(eval(input()))
            sys.stdout.write(str(os.path.exists('x')) + os.linesep)
            if 'ONLINE_JUDGE' in os.environ: pass
            """;

    private static boolean rejected(String source) {
        return SourcePolicies.check("python3", source).isPresent();
    }

    @Test
    void anOrdinarySolutionPassesInBothVersions() {
        assertThat(SourcePolicies.check("python3", FAST_IO)).isEmpty();
        assertThat(SourcePolicies.check("python2", FAST_IO)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import subprocess\n",
            "import socket\n",
            "import ctypes\n",
            "import sys, socket\n",
            "import sys as s, socket as t\n",
            "from subprocess import run\n",
            "from os.path import exists; import ctypes\n",
            "def f():\n    import pickle\n",
            "if True: import multiprocessing\n",
            "import \\\n  socket\n",
            "from importlib import import_module\n",
    })
    void aDangerousModuleIsRejected(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import os\nos.system('sh')\n",
            "import os\nos.popen('sh')\n",
            "import os\nos.fork()\n",
            "import os\nos.execv('/bin/sh', [])\n",
            "import os\nos.kill(1, 9)\n",
            "import os\nos.remove('x')\n",
            "import os\nos . system('sh')\n",
            "import os as o\n",
            "from os import system\n",
            "from os import *\n",
            "from os import (read, system)\n",
    })
    void theDangerousPartOfOsIsRejected(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "__import__('os')\n",
            "print(__builtins__)\n",
            "().__class__.__bases__[0].__subclasses__()\n",
            "f.__globals__\n",
            "exec('1')\n",
            "compile('1', 'a', 'eval')\n",
            "open('/etc/passwd')\n",
            "open(name)\n",
            "f'{__import__(\"os\")}'\n",
    })
    void anIntrospectionOrFileAccessIsRejected(String source) {
        assertThat(rejected(source)).isTrue();
    }

    @Test
    void aTripleQuotedStringEndsWhereItEnds() {
        assertThat(rejected("x = '''a'''\nimport socket\n")).isTrue();
    }
}
