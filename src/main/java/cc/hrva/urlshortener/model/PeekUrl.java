package cc.hrva.urlshortener.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeekUrl {

  @Schema(description = "The original long URL", example = "https://example.com/very-long-url")
  private String longUrl;

  @Schema(description = "Short URL alias", example = "abc123")
  private String shortUrl;

  @Schema(description = "Creation date and time", example = "2026-05-16T10:00:00")
  private LocalDateTime createDate;

  @Schema(description = "Whether the link needs a password; its destination is then left out", example = "false")
  private boolean passwordProtected;

}
