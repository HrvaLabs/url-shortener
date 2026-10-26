package cc.hrva.urlshortener.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateUrlDtoValidationTest {

    private static final String ALIAS_CHARACTERS = "Custom short URL can only contain letters, numbers, hyphens and underscores";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void anAtSignIsNeverPartOfAnAlias() {
        assertThat(violations("@abc")).containsExactly(ALIAS_CHARACTERS);
        assertThat(violations("a@b")).containsExactly(ALIAS_CHARACTERS);
        assertThat(violations("abc@")).containsExactly(ALIAS_CHARACTERS);
        assertThat(violations("@")).containsExactly(ALIAS_CHARACTERS);
        assertThat(violations("abc")).isEmpty();
    }

    private List<String> violations(final String alias) {
        final var dto = CreateUrlDto.builder().longUrl("https://example.com").shortUrl(alias).build();
        return validator.validate(dto).stream().map(ConstraintViolation::getMessage).toList();
    }

}
