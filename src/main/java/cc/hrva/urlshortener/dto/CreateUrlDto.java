package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.validator.UrlValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class CreateUrlDto {

  @NotBlank(message = "Long URL is required")
  @Size(max = UrlValidator.MAX_LONG_URL_LENGTH, message = UrlValidator.LONG_URL_TOO_LONG)
  @Schema(description = "The original long URL to shorten", example = "https://example.com/very-long-url")
  private String longUrl;
  @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "Custom short URL can only contain letters, numbers, hyphens and underscores")
  @Size(max = 32, message = "Custom short URL must be 32 characters or fewer")
  @Schema(description = "Custom short URL alias (optional)", example = "my-custom-alias")
  private String shortUrl;
  @Schema(description = "Maximum number of visits allowed", example = "100")
  private Long visitLimit;
  @Schema(description = "Expiration date and time of the URL", example = "2026-12-31T23:59:59")
  private LocalDateTime expirationDate;
  @Schema(description = "Tags for organizing the URL (max 10, each ≤30 chars, normalized to lowercase)", example = "[\"work\", \"campaign\"]")
  private Set<String> tags;
  @Schema(description = "Optional password to protect the link (registered users only; ignored for anonymous)", example = "s3cret")
  private String password;
  @Size(max = 253, message = UrlValidator.DOMAIN_NOT_USABLE)
  @Schema(description = "One of your active custom domains to put the link on (optional, signed-in only); empty means hrva.cc", example = "go.example.com")
  private String domain;

}
