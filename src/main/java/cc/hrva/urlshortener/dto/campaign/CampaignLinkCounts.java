package cc.hrva.urlshortener.dto.campaign;

import java.time.LocalDateTime;

public record CampaignLinkCounts(long campaignId, long total, long active, long held, long blocked, long domains, LocalDateTime firstLinkCreated) {
}
