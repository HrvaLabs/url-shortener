package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.ReviewAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record ReviewBulkResolveDto(
        @NotNull(message = ReviewBulkResolveDto.LINKS_REQUIRED)
        @Size(min = 1, max = ReviewBulkResolveDto.MAX_LINKS, message = ReviewBulkResolveDto.LINKS_REQUIRED)
        @UniqueElements(message = ReviewBulkResolveDto.LINKS_REPEATED)
        List<@NotNull(message = ReviewBulkResolveDto.LINK_ID_REQUIRED) Long> urlIds,
        @NotNull(message = ReviewResolveDto.ACTION_REQUIRED) ReviewAction action) {

    public static final int MAX_LINKS = 100;
    public static final String LINKS_REQUIRED = "Select between 1 and 100 links";
    public static final String LINKS_REPEATED = "Select each link only once";
    public static final String LINK_ID_REQUIRED = "Every selected link needs its id";

}
