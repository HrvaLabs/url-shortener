package cc.hrva.urlshortener.dto.campaign;

public record CampaignDomainResponse(String domain, long links, ReportStatus report) {
}
