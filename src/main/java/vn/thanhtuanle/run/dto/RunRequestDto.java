package vn.thanhtuanle.run.dto;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** What the portal sends to {@code POST /submissions/run} (Run code design §5). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunRequestDto {

    private String sourceCode;

    private String languageIdentifier;

    private String problemSlug;

    /** The contest this is run in; absent for practice. Same rules as a submit. */
    @Pattern(regexp = "^[a-z0-9-]{3,64}$")
    private String contestSlug;

    /** 1 to {@code oj.run.max-cases} cases; the limits are {@link vn.thanhtuanle.run.RunRequestValidator}'s. */
    private List<RunCaseDto> cases;
}
