package vn.thanhtuanle.judge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.judge.dto.*;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeService {

    private static final int MAX_CODE_LENGTH = 10240;
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /**
     * Languages whose runtime reserves a large virtual-memory space (so a hard memory rlimit
     * would kill them before user code runs). For these, judge_server only *checks* memory
     * usage instead of enforcing it via rlimit. Mirrors QingdaoU's per-language config.
     */
    private static final List<String> MEMORY_CHECK_ONLY_LANGUAGES = List.of("java", "go");

    /**
     * Per-language environment on top of judge-server's own (it adds PATH to the compiler's, nothing else),
     * as QingdaoU configures them. `go build` needs a build cache and the judge sets no HOME; at run time
     * madvdontneed hands freed pages straight back, so the measured memory is what the program holds.
     */
    private static final Map<String, List<String>> COMPILE_ENV = Map.of(
            "go", List.of("GOCACHE=/tmp", "GOPATH=/root/go"));
    private static final Map<String, List<String>> RUN_ENV = Map.of(
            "go", List.of("GODEBUG=madvdontneed=1"));

    /*
     * A checker, as qduoj compiles and runs it. qduoj formats the braces itself: the binary is cached per sandbox as
     * /judger/spj/spj-<version> and runs as `spj <input file> <user output file>`. It is compiled inside the first
     * judge request that needs it, so it gets more time than a submission's compile.
     */
    private static final String SPJ_EXE_NAME = "spj-{spj_version}";
    private static final String SPJ_SRC_NAME = "spj-{spj_version}.cpp";
    private static final String SPJ_COMMAND = "{exe_path} {in_file_path} {user_out_file_path}";
    private static final String SPJ_SECCOMP_RULE = "c_cpp";
    private static final int SPJ_COMPILE_MAX_CPU_TIME_MS = 10000;
    private static final int SPJ_COMPILE_MAX_REAL_TIME_MS = 20000;

    private static final List<String> BLACKLISTED_KEYWORDS = List.of(
            "Runtime.getRuntime().exec",
            "/bin/sh",
            "ProcessBuilder",
            "java.lang.Runtime");

    public void validate(String sourceCode) {
        log.info("Start validate source code");
        if (sourceCode == null || sourceCode.isEmpty()) {
            throw new IllegalArgumentException("Source code cannot be empty");
        }
        if (sourceCode.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("Source code length exceeds the limit");
        }
        for (String keyword : BLACKLISTED_KEYWORDS) {
            if (sourceCode.contains(keyword)) {
                throw new IllegalArgumentException("Source code contains blacklisted keyword: " + keyword);
            }
        }
        log.info("End validate source code");
    }

    /** A problem without a checker. */
    public SubmissionRequestedEvent buildRequestedEvent(String submissionId, String sourceCode,
            JudgeSpec spec, Language language, int rejudgeCount) {
        return buildRequestedEvent(submissionId, sourceCode, spec, language, rejudgeCount, null);
    }

    /**
     * @param rejudgeCount    the submission's generation: 0 for a submit, its {@code rejudge_count} after a reset
     * @param checkerLanguage the language the problem's checker is compiled with ({@link CheckerLanguages}); required
     *                        when {@code spec} has a checker, unused otherwise
     */
    public SubmissionRequestedEvent buildRequestedEvent(String submissionId, String sourceCode,
            JudgeSpec spec, Language language, int rejudgeCount, Language checkerLanguage) {
        log.info("Building judge request for problem: {}", spec.problemSlug());

        JudgeCompileConfigDto compileConfig = JudgeCompileConfigDto.builder()
                .srcName(language.getSrcName())
                .exeName(language.getExeName())
                .maxCpuTime(3000)
                .maxRealTime(5000)
                .maxMemory(language.getCompileMaxMemory())
                .compileCommand(language.getCompileCommand())
                .env(COMPILE_ENV.getOrDefault(language.getIdentifier(), List.of()))
                .build();

        int memoryCheckOnly = MEMORY_CHECK_ONLY_LANGUAGES.contains(language.getIdentifier()) ? 1 : 0;
        List<String> runEnv = new ArrayList<>(RUN_ENV.getOrDefault(language.getIdentifier(), List.of()));
        runEnv.addAll(AppProperties.JUDGE_ENV);
        JudgeRunConfigDto runConfig = JudgeRunConfigDto.builder()
                .command(language.getRunCommand())
                .seccompRule(language.getSeccompRule())
                .env(runEnv)
                .memoryLimitCheckOnly(memoryCheckOnly)
                .build();

        JudgeLanguageConfigDto languageConfig = JudgeLanguageConfigDto.builder()
                .compile(compileConfig)
                .run(runConfig)
                .build();

        SubmissionRequestedEvent.SubmissionRequestedEventBuilder event = SubmissionRequestedEvent.builder()
                .submissionId(submissionId)
                .src(sourceCode)
                .languageConfig(languageConfig)
                .maxCpuTime(spec.timeLimitMs())
                .maxMemory(spec.memoryLimitMb() * BYTES_PER_MB)
                .testCaseId(spec.problemSlug() + "__" + spec.testCaseVersion())
                .output(true)
                .rejudgeCount(rejudgeCount);
        if (spec.spjVersion() != null) {
            event.spjVersion(spec.spjVersion())
                    .spjSrc(spec.spjSource())
                    .spjConfig(new JudgeSpjConfigDto(SPJ_EXE_NAME, SPJ_COMMAND, SPJ_SECCOMP_RULE))
                    .spjCompileConfig(JudgeCompileConfigDto.builder()
                            .env(List.of())
                            .srcName(SPJ_SRC_NAME)
                            .exeName(SPJ_EXE_NAME)
                            .maxCpuTime(SPJ_COMPILE_MAX_CPU_TIME_MS)
                            .maxRealTime(SPJ_COMPILE_MAX_REAL_TIME_MS)
                            .maxMemory(checkerLanguage.getCompileMaxMemory())
                            .compileCommand(checkerLanguage.getCompileCommand())
                            .build());
        }
        return event.build();
    }
}
