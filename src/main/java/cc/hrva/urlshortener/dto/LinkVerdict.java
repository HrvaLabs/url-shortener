package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.BlockReason;
import java.util.List;

public record LinkVerdict(Outcome outcome, List<String> reasons, BlockReason blockReason, String blockDetail) {

    public static LinkVerdict clean() {
        return new LinkVerdict(Outcome.CLEAN, List.of(), null, null);
    }

    public enum Outcome {
        ALLOWED, BLOCK, FLAG, CLEAN
    }

}
