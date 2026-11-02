package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.validator.constraint.PasswordFitsBcrypt;
import cc.hrva.urlshortener.validator.UrlValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class UrlUpdateDto {

    @NotNull(message = "ID is required")
    @Schema(description = "Unique identifier of the URL to update", example = "1")
    private Long id;
    @Size(max = UrlValidator.MAX_LONG_URL_LENGTH, message = UrlValidator.LONG_URL_TOO_LONG)
    @Schema(description = "New destination URL; null or blank leaves it unchanged. It gets the same checks as a new link. Owners can change it on a "
            + "custom domain link, and on a hrva.cc link while the account is in good standing (see GET /api/v1/urls/{id}/destination-change); "
            + "at most 5 changes per link a day", example = "https://example.com/new-page")
    private String longUrl;
    @Schema(description = "How many clicks the link allows: every click counts, not counting bots and HEAD requests. Null or 0 removes the limit",
            example = "200")
    private Long visitLimit;
    @Schema(description = "Expiration date and time. Null removes it. A new one can't be in the past; the current one is kept as sent, even when it "
            + "has passed, unless active is true", example = "2026-12-31T23:59:59Z")
    private LocalDateTime expirationDate;
    @Schema(description = "Tags (replaces existing when provided; null leaves them unchanged)", example = "[\"work\"]")
    private Set<String> tags;
    @Schema(description = "Password: non-empty sets/replaces it, empty string clears it, null leaves it unchanged", example = "s3cret")
    @PasswordFitsBcrypt
    private String password;
    @Schema(description = "true turns the link back on after the save, with the same checks as a new link; false turns it off; null leaves it as it is",
            example = "true")
    private Boolean active;
    @Schema(description = "Admins only: true saves a new destination the link checks would block or send to review (the 409 answer lists why); "
            + "the change is marked as forced. Ignored for owners", example = "false")
    private Boolean force;

}
