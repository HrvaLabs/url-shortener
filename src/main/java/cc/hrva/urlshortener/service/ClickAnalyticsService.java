package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ClickRequest;

public interface ClickAnalyticsService {

    void recordClick(Long urlId, String shortUrl, ClickRequest click);
    boolean isBot(ClickRequest click);

}
