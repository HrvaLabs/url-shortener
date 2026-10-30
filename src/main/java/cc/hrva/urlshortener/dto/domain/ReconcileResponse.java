package cc.hrva.urlshortener.dto.domain;

import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.ReconcileRun;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record ReconcileResponse(
        LocalDateTime ranAt,
        Integer cloudflareTotal,
        Integer oursInCloudflare,
        List<String> unknownHostnames,
        List<MissingDomain> missing,
        List<String> ignored,
        String error) {

    public ReconcileResponse {
        unknownHostnames = List.copyOf(unknownHostnames);
        missing = List.copyOf(missing);
        ignored = List.copyOf(ignored);
    }

    public static ReconcileResponse of(final ReconcileRun run, final List<String> ignored) {
        return new ReconcileResponse(
                run.ranAt().truncatedTo(ChronoUnit.SECONDS),
                run.cloudflareTotal(),
                run.oursInCloudflare(),
                run.unknownHostnames(),
                run.missingDomains().stream().map(missing -> new MissingDomain(missing.id(), missing.hostname())).toList(),
                ignored,
                run.error());
    }

    public record MissingDomain(long id, String hostname) {
    }

}
