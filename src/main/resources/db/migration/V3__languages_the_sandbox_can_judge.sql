-- Language rows the qduoj/judge-server sandbox (2020-07, Judger 2.1.1, Ubuntu 18.04) can actually judge,
-- each limit checked by judging a+b on that image.
--
-- C++: the c_cpp seccomp filter, as C already has (it was NULL, so contestants' C++ ran unfiltered);
-- and room to compile: <bits/stdc++.h> alone takes the compiler ~180 MB, past the 128 MB it had, which made
-- every such submission a compile error. 1 GiB is QingdaoU's own C++ compile limit.
UPDATE t_languages SET seccomp_rule = 'c_cpp', compile_max_memory = 1073741824 WHERE identifier = 'cpp';

-- Go: the toolchain's runtime reserves more address space than any compile rlimit allows ("failed to reserve
-- page summary memory" at 128 MB; threads fail to start at 1 GiB; 2 GiB+ is rejected by the judger), so the
-- compile is bounded by its CPU/real-time limits only, as javac's already is (-1).
UPDATE t_languages SET compile_max_memory = -1 WHERE identifier = 'go';

-- The image has neither node nor php, so these could never be judged: off the list, and refused on submit.
UPDATE t_languages SET is_disabled = true WHERE identifier IN ('javascript', 'php');
