package vn.thanhtuanle.run.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One case of a run: its input, and the output to compare with — absent on a custom case the user typed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunCaseDto {

    private String input;

    private String expectedOutput;
}
