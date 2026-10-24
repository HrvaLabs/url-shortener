package cc.hrva.urlshortener.dto;

public record LinkClicks(long urlId, long clicks, long uniqueVisitors) {
}
