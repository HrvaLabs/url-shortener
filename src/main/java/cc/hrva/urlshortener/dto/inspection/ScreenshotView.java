package cc.hrva.urlshortener.dto.inspection;

import cc.hrva.urlshortener.model.enums.InspectionState;
import java.time.LocalDateTime;

public record ScreenshotView(
        InspectionState state,
        LocalDateTime requestedAt,
        LocalDateTime takenAt,
        String screenshotUrl,
        String pageTitle,
        Integer pageStatus,
        String failure,
        boolean configured) {

    public static ScreenshotView none(final boolean configured) {
        return new ScreenshotView(null, null, null, null, null, null, null, configured);
    }

}
