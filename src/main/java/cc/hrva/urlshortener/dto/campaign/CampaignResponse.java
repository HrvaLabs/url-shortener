package cc.hrva.urlshortener.dto.campaign;

import cc.hrva.urlshortener.model.enums.CampaignStatus;
import java.time.LocalDateTime;
import java.util.List;

public record CampaignResponse(long id, String shape, String path, List<String> queryNames, CampaignStatus status, boolean holding,
                               LocalDateTime firstSeenAt, LocalDateTime lastSeenAt, LocalDateTime activeUntil, String allowedBy,
                               LocalDateTime allowedAt, Links links, long domains, long clicks, Reports reports) {

    public record Links(long total, long active, long held, long blocked) {
    }

    public record Reports(long reported, long suspended) {
    }

}
