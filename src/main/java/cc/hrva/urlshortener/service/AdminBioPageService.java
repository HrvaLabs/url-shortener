package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.bio.AdminBioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioHandleRevokeDto;
import cc.hrva.urlshortener.dto.bio.BioPageReasonDto;
import cc.hrva.urlshortener.dto.bio.BioPageReviewResolveDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminBioPageService {

    Page<AdminBioPageResponse> search(String search, boolean hiddenOnly, Pageable pageable);
    AdminBioPageResponse hide(long id, BioPageReasonDto bioPageReasonDto);
    AdminBioPageResponse unhide(long id);
    AdminBioPageResponse changeHandle(long id, BioHandleDto bioHandleDto);
    AdminBioPageResponse revokeHandle(long id, BioHandleRevokeDto bioHandleRevokeDto);
    void resolveReview(long pageId, BioPageReviewResolveDto bioPageReviewResolveDto);

}
