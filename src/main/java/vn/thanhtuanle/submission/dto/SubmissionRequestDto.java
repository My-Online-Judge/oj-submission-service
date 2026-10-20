package vn.thanhtuanle.submission.dto;

import jakarta.validation.constraints.Pattern;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionRequestDto {

    private String sourceCode;

    private String languageIdentifier;

    private String problemSlug;

    private Boolean shareSubmission = false;

    /** The contest this is submitted in; absent for practice. */
    @Pattern(regexp = "^[a-z0-9-]{3,64}$")
    private String contestSlug;
}
