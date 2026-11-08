-- Python 3 compiles to a flat solution.pyc instead of __pycache__/solution.cpython-36.pyc. V2's name was whatever
-- `python3 -m py_compile` wrote on the qduoj image, whose Python is 3.6, so it pinned that CPython version; the Go
-- sandbox's Python 3 is 3.10 and would write __pycache__/solution.cpython-310.pyc, which that row never finds.
-- py_compile.compile(..., cfile=...) writes the .pyc exactly where {exe_path} says, whatever the interpreter version.
--
-- The -c argument has no space inside it on purpose: both sandboxes split a command on single spaces. It runs
-- unchanged on qduoj's Python 3.6, so this migration is safe before and after the sandbox cut-over.
-- doraise=True makes a syntax error exit non-zero, so it is still a compile error.
UPDATE t_languages
SET compile_command = '/usr/bin/python3 -c __import__(''py_compile'').compile(''{src_path}'',cfile=''{exe_path}'',doraise=True)',
    exe_name = 'solution.pyc',
    updated_at = now()
WHERE identifier = 'python3';
