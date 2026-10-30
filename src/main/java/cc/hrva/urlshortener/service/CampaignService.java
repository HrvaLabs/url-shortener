package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.service.campaign.UrlShape;
import java.util.Optional;

public interface CampaignService {

    Optional<Long> holdingCampaignId(UrlShape shape);

    void addHeldLink(long campaignId, long urlId, String domain);

    void recordAnonymousLink(long urlId, String longUrl, LinkVerdict.Outcome outcome);

}
