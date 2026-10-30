package cc.hrva.urlshortener.dto.domain;

public record DomainSummaryResponse(
        long slotsInUse,
        int maxActive,
        int freeAllowance,
        Integer cloudflareTotal,
        CloudflareQuota cloudflareQuota,
        long pending,
        long active,
        CloudflareCheckResponse token,
        ReconcileResponse reconcile) {

    public record CloudflareQuota(int used, int allocated) {
    }

}
