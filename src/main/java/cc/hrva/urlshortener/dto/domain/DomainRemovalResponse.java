package cc.hrva.urlshortener.dto.domain;

import java.util.List;

public record DomainRemovalResponse(int moved, List<DomainRename> renamed, int deactivated) {

    public DomainRemovalResponse {
        renamed = List.copyOf(renamed);
    }

    public static DomainRemovalResponse nothing() {
        return new DomainRemovalResponse(0, List.of(), 0);
    }

    public record DomainRename(String from, String to) {
    }

}
