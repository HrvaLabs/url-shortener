package cc.hrva.urlshortener.service.alias;

public record AliasDecision(AliasStatus status, String reason) {

    public static AliasDecision available() {
        return new AliasDecision(AliasStatus.AVAILABLE, null);
    }

    public boolean usable() {
        return status == AliasStatus.AVAILABLE;
    }
}
