package cc.hrva.urlshortener.dto;

public record ReputationResult(Verdict verdict, String type) {

    public static ReputationResult none() {
        return new ReputationResult(Verdict.NONE, null);
    }

    public enum Verdict {
        BLOCK, FLAG, NONE
    }

}
