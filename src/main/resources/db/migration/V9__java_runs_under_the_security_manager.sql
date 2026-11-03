-- Java had neither a seccomp rule (the JVM cannot run under one of the sandbox's) nor a security manager, so a program
-- could start processes and open sockets. The policy is sandbox/java.policy in judge-deployment, mounted into every
-- sandbox: deploy that first. Without the file the JVM denies everything, fast I/O included, rather than running open.
UPDATE t_languages
SET run_command = '/usr/bin/java -cp {exe_dir} -Xss1M -Xmx256m -Djava.awt.headless=true -Djava.security.manager -Dfile.encoding=UTF-8 -Djava.security.policy==/opt/oj/java.policy Main'
WHERE identifier = 'java';
