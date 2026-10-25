package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse;

public interface AdminStatsService {

    TrafficStatsResponse traffic(Integer days);

    LinksStatsResponse links(Integer days);

    UsersStatsResponse users(Integer days);

}
