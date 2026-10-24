package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AnalyticsOverviewDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;

public interface AnalyticsService {

    AnalyticsOverviewDto getOverview(Integer days);
    UrlAnalyticsDto getUrlAnalytics(Long urlId, Integer days);
    UrlAnalyticsDto getUrlAnalyticsForOwner(Long urlId, Integer days);

}
