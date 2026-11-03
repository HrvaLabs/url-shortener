package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.dto.inspection.LinkInspectionResponse;
import cc.hrva.urlshortener.dto.inspection.ScreenshotView;
import cc.hrva.urlshortener.model.LinkScreenshot;
import cc.hrva.urlshortener.service.alert.LinkReviewOpened;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface LinkInspectionService {

    void requestScreenshot(long urlId);

    void takeScreenshot(long urlId);

    LinkInspectionResponse inspection(long urlId);

    Optional<LinkScreenshot> screenshot(long urlId);

    Map<Long, ScreenshotView> summaries(Collection<Long> urlIds);

    void dropFor(long urlId);

    void onReviewOpened(LinkReviewOpened event);

}
