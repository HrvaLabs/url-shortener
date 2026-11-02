package cc.hrva.urlshortener.dto;

import java.util.List;

public record AccountStanding(List<MissingRequirement> missing) {

    public boolean good() {
        return missing.isEmpty();
    }

}
