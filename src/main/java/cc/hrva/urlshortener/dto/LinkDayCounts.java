package cc.hrva.urlshortener.dto;

public record LinkDayCounts(long urlId, long clicks, long uniqueVisitors, long botClicks) {
}
