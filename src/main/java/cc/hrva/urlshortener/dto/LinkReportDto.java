package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkReportDto {

    @Schema(description = "The reported short link, as abc, hrva.cc/abc or https://hrva.cc/abc, or bio page, as @name, hrva.cc/@name or app.hrva.cc/bio/name", example = "hrva.cc/abc")
    private String shortUrl;
    @Schema(description = "What is wrong with the link or bio page; IMPERSONATION when it pretends to be someone",
            allowableValues = {"PHISHING", "MALWARE", "SPAM", "IMPERSONATION", "OTHER"}, example = "PHISHING")
    private String category;
    @Schema(description = "Optional details for the reviewer, at most 500 characters", example = "Asks for my bank login")
    private String details;

}
