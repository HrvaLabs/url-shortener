package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ClickRequest;

public interface ClickAnalyticsService {

    void recordClick(Long urlId, String cacheKey, ClickRequest click);
    boolean isBot(ClickRequest click);

}
