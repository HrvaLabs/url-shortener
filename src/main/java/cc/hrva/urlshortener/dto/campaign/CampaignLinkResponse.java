package cc.hrva.urlshortener.dto.campaign;

import cc.hrva.urlshortener.model.enums.BlockReason;
import java.time.LocalDateTime;

public record CampaignLinkResponse(long id, String shortUrl, String longUrl, String domain, LocalDateTime createDate, boolean active,
                                   BlockReason blockReason, long clicks) {
}
