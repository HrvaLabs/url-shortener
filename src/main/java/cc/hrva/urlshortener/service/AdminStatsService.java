package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;

public interface AdminStatsService {

    TrafficStatsResponse traffic(Integer days);

    LinksStatsResponse links(Integer days);

}
