package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.bio.BioPageAnalyticsResponse;

public interface BioPageAnalyticsService {

    BioPageAnalyticsResponse analytics(Integer days);

}
