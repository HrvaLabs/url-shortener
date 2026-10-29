package cc.hrva.urlshortener.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record DomainBlockPreviewDto(
        @NotNull(message = ReviewBulkResolveDto.LINKS_REQUIRED)
        @Size(min = 1, max = ReviewBulkResolveDto.MAX_LINKS, message = ReviewBulkResolveDto.LINKS_REQUIRED)
        @UniqueElements(message = ReviewBulkResolveDto.LINKS_REPEATED)
        List<@NotNull(message = ReviewBulkResolveDto.LINK_ID_REQUIRED) Long> urlIds) {
}
