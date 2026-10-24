package cc.hrva.urlshortener.dto;

import java.time.LocalDate;

public record AnalyticsDay(LocalDate date, long clicks, long uniqueVisitors, long botClicks) {
}
