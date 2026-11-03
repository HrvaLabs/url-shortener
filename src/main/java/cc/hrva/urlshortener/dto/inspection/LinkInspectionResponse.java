package cc.hrva.urlshortener.dto.inspection;

import java.util.List;

public record LinkInspectionResponse(
        long urlId,
        String longUrl,
        List<String> redirectChain,
        ScreenshotView screenshot) {
}
