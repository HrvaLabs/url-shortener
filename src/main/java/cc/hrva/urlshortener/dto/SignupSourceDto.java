package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.SignupSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignupSourceDto {

    @NotNull(message = "Source must not be empty")
    @Schema(description = "How the account found hrva.cc; SKIPPED when the question was dismissed", example = "AI_ASSISTANT")
    private SignupSource source;

}
