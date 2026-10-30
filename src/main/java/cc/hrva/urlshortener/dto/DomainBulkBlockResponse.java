package cc.hrva.urlshortener.dto;

import java.util.List;

public record DomainBulkBlockResponse(List<Blocked> blocked, List<Skipped> skipped, int linksDeactivated, int heldLinksConverted) {

    public record Blocked(String domain, int linksDeactivated, int domainsDisabled, int heldLinksConverted) {
    }

    public record Skipped(String domain, String reason) {
    }

}
