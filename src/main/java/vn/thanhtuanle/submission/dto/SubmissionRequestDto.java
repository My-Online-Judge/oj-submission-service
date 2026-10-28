package vn.thanhtuanle.submission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;
import vn.thanhtuanle.judge.JudgeService;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionRequestDto {

    @NotBlank(message = "Source code is required")
    @Size(max = JudgeService.MAX_CODE_LENGTH, message = "Source code is too long")
    private String sourceCode;

    @NotBlank(message = "Language is required")
    private String languageIdentifier;

    @NotBlank(message = "Problem is required")
    private String problemSlug;

    private Boolean shareSubmission = false;

    /** The contest this is submitted in; absent for practice. */
    @Pattern(regexp = "^[a-z0-9-]{3,64}$")
    private String contestSlug;
}
