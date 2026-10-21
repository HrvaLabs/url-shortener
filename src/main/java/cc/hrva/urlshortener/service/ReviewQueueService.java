package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReviewQueueService {

    void flag(Url url, List<String> reasons);
    void resolveOpen(Url url, ReviewResolution resolution, String resolvedBy);
    Page<LinkReview> open(Pageable pageable, String reason);
    long openCount();

}
