package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;

public interface AdminStatsService {

    TrafficStatsResponse traffic(Integer days);

}
