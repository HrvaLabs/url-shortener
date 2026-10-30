package cc.hrva.urlshortener.dto.campaign;

import java.util.List;

public record CampaignDetailResponse(CampaignResponse campaign, List<CampaignDomainResponse> domains) {
}
