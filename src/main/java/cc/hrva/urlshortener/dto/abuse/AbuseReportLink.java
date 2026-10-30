package cc.hrva.urlshortener.dto.abuse;

import java.time.LocalDateTime;

public record AbuseReportLink(Long id, String longUrl, String finalUrl, LocalDateTime createDate, boolean active) {
}
