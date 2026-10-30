package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.campaign.CampaignDetailResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignLinkResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignResponse;
import cc.hrva.urlshortener.service.campaign.UrlShape;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CampaignService {

    Optional<Long> holdingCampaignId(UrlShape shape);

    boolean confirmHold(long campaignId);

    void addHeldLink(long campaignId, long urlId, String domain);

    void recordAnonymousLink(long urlId, String longUrl, LinkVerdict.Outcome outcome);

    Page<CampaignResponse> campaigns(String search, Pageable pageable);

    CampaignDetailResponse detail(long id);

    Page<CampaignLinkResponse> links(long id, String search, Pageable pageable);

    int allow(long id, String performedBy);

    List<DomainBlockPreviewResponse> blockPreview(long id);

    List<Long> linkIds(long id);

}
