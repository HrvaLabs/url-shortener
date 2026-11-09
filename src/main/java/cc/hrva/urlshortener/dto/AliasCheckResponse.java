package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.service.alias.AliasStatus;

public record AliasCheckResponse(String name, AliasStatus status, String reason) {
}
