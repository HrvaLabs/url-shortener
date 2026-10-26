package cc.hrva.urlshortener.dto.stats;

import cc.hrva.urlshortener.model.enums.BlocklistAction;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PublicStatsResponse(
        LocalDate from,
        LocalDate to,
        long activeLinks,
        long linksCreated,
        long clicks,
        List<DailyClicks> daily,
        BlockedLinks blocked,
        long visitorReportsHandled,
        Long averageReviewMinutes,
        List<Blocklist> blocklists,
        LocalDateTime updatedAt) {

    public record DailyClicks(LocalDate date, long clicks) {}

    public record BlockedLinks(long allTime, long last30Days) {}

    public record Blocklist(String name, BlocklistAction action, long entries) {}

}
