package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.bio.BioPageReviewEntryResponse;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.enums.BioPageReviewResolution;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BioPageReviewService {

    void flag(BioPage page, List<String> reasons);
    void report(BioPage page, ReportCategory category, String details);
    void resolveOpen(BioPage page, BioPageReviewResolution resolution, String resolvedBy);
    Page<BioPageReviewEntryResponse> openEntries(Pageable pageable);
    long openCount();
    Set<Long> openPageIds(Collection<Long> pageIds);

}
