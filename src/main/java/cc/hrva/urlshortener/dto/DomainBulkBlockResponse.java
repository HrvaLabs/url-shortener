package cc.hrva.urlshortener.dto;

import java.util.List;

public record DomainBulkBlockResponse(List<Blocked> blocked, List<Skipped> skipped, int linksDeactivated) {

    public record Blocked(String domain, int linksDeactivated, int domainsDisabled) {
    }

    public record Skipped(String domain, String reason) {
    }

}
