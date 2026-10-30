package cc.hrva.urlshortener.dto.abuse;

import cc.hrva.urlshortener.dto.ReviewBulkResolveDto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record AbuseReportRequest(
        @Size(min = 1, max = ReviewBulkResolveDto.MAX_LINKS, message = ReviewBulkResolveDto.LINKS_REQUIRED)
        @UniqueElements(message = ReviewBulkResolveDto.LINKS_REPEATED)
        List<@NotNull(message = ReviewBulkResolveDto.LINK_ID_REQUIRED) Long> urlIds,
        Long campaignId) {
}
