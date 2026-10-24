package vn.thanhtuanle.run;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.run.dto.RunCaseDto;
import vn.thanhtuanle.run.dto.RunRequestDto;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The limits of a run's cases (Run code design §5) and their normalisation: CR LF and a lone CR become LF in every
 * input and expected output, nothing else (the comparison stays the sandbox's). A refusal is an
 * {@link IllegalArgumentException}, which the shared handler answers as 400 with the message. The source code is
 * checked elsewhere ({@code JudgeService.validate}).
 */
@Component
@RequiredArgsConstructor
public class RunRequestValidator {

    private final RunProperties props;

    /** The cases, normalised, in request order; or throws. */
    public List<RunCaseDto> validate(RunRequestDto req) {
        List<RunCaseDto> cases = req.getCases();
        if (cases == null || cases.isEmpty() || cases.size() > props.getMaxCases()) {
            throw new IllegalArgumentException("Run needs between 1 and " + props.getMaxCases() + " cases");
        }
        List<RunCaseDto> normalised = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            RunCaseDto c = cases.get(i);
            int n = i + 1;
            if (c == null || c.getInput() == null) {
                throw new IllegalArgumentException("Case " + n + " input is required");
            }
            if (bytes(c.getInput()) > props.getMaxCaseBytes()) {
                throw new IllegalArgumentException("Case " + n + " input exceeds " + props.getMaxCaseBytes() + " bytes");
            }
            if (c.getExpectedOutput() != null && bytes(c.getExpectedOutput()) > props.getMaxCaseBytes()) {
                throw new IllegalArgumentException(
                        "Case " + n + " expected output exceeds " + props.getMaxCaseBytes() + " bytes");
            }
            normalised.add(new RunCaseDto(lf(c.getInput()), lf(c.getExpectedOutput())));
        }
        return normalised;
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String lf(String s) {
        return s == null ? null : s.replace("\r\n", "\n").replace('\r', '\n');
    }
}
