package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AnalyticsOverviewDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;

public interface AnalyticsService {

    AnalyticsOverviewDto getOverview();
    UrlAnalyticsDto getUrlAnalytics(Long urlId);
    UrlAnalyticsDto getUrlAnalyticsForOwner(Long urlId);

}
