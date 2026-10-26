package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse;
import cc.hrva.urlshortener.dto.stats.SystemStatsResponse;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse;
import java.time.LocalDate;

public interface AdminStatsService {

    TrafficStatsResponse traffic(Integer days);

    LinksStatsResponse links(Integer days);

    UsersStatsResponse users(Integer days);

    SafetyStatsResponse safety(Integer days);

    SystemStatsResponse system(Integer days);

    TrafficStatsResponse traffic(LocalDate from, LocalDate to);

    LinksStatsResponse links(LocalDate from, LocalDate to);

    UsersStatsResponse users(LocalDate from, LocalDate to);

    SafetyStatsResponse safety(LocalDate from, LocalDate to);

}
