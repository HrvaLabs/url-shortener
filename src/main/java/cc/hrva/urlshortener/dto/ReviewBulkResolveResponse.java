package cc.hrva.urlshortener.dto;

import java.util.List;

public record ReviewBulkResolveResponse(List<Long> resolved, List<Failure> failed) {

    public record Failure(long urlId, String error) {
    }

}
