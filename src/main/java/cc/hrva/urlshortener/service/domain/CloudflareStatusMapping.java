package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareHostname;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public final class CloudflareStatusMapping {

    static final int MAX_ERROR_LENGTH = 500;
    private static final Duration DNS_GRACE = Duration.ofMinutes(10);
    private static final String ACTIVE = "active";
    private static final String ACTIVE_REDEPLOYING = "active_redeploying";
    private static final String TIMED_OUT_SUFFIX = "_timed_out";
    private static final String EXPIRED = "expired";
    private static final String CAA = "CAA";
    private static final String GONE = "Cloudflare no longer has this hostname";
    private static final Set<String> BLOCKED = Set.of("blocked", "pending_blocked");
    private static final Set<String> MOVED_AWAY = Set.of("moved", "deleted", "pending_deletion");

    private CloudflareStatusMapping() {
    }

    public static Transition next(final CustomDomainStatus current, final boolean wasActive, final CloudflareHostname hostname,
                                  final LocalDateTime approvedAt, final LocalDateTime now) {
        final var status = StringUtils.defaultString(hostname.status());
        final var sslStatus = hostname.sslStatus();
        final var caa = hostname.validationErrors().stream().anyMatch(error -> Strings.CS.contains(error, CAA));
        final var lastError = lastErrorOf(hostname);
        if (BLOCKED.contains(status)) {
            return new Transition(CustomDomainStatus.ERROR, DomainProblem.CLOUDFLARE_BLOCKED, lastError);
        }
        if (MOVED_AWAY.contains(status)) {
            return wasActive
                    ? new Transition(CustomDomainStatus.ERROR, DomainProblem.CNAME_REMOVED, lastError)
                    : new Transition(CustomDomainStatus.AWAITING_DNS, DomainProblem.DNS_NOT_FOUND, lastError);
        }
        if (timedOut(sslStatus)) {
            return new Transition(CustomDomainStatus.ERROR, caa ? DomainProblem.CAA_BLOCKED : DomainProblem.CERTIFICATE_TIMED_OUT, lastError);
        }
        if (ACTIVE.equals(status)) {
            return ACTIVE.equals(sslStatus)
                    ? new Transition(CustomDomainStatus.ACTIVE, null, lastError)
                    : new Transition(CustomDomainStatus.AWAITING_DNS, caa ? DomainProblem.CAA_BLOCKED : null, lastError);
        }
        if (current == CustomDomainStatus.ACTIVE && (ACTIVE_REDEPLOYING.equals(status) || !known(status))) {
            return new Transition(CustomDomainStatus.ACTIVE, null, lastError);
        }
        return new Transition(CustomDomainStatus.AWAITING_DNS, pendingProblem(caa, approvedAt, now), lastError);
    }

    public static Transition gone() {
        return new Transition(CustomDomainStatus.ERROR, DomainProblem.PROVISIONING_FAILED, GONE);
    }

    public static String failureText(final CloudflareResult<?> result) {
        return cut(switch (result) {
            case CloudflareResult.Refused<?>(final var status, final var code, final var message) -> StringUtils.isBlank(message)
                    ? "Cloudflare refused the request (HTTP %d, code %d)".formatted(status, code)
                    : message;
            case CloudflareResult.Unreachable<?>(final var failure) -> "Cloudflare couldn't be reached (%s)".formatted(failure);
            case CloudflareResult.NotFound<?> notFound -> "Cloudflare answered 404";
            case CloudflareResult.Ok<?> ok -> null;
        });
    }

    public static String cut(final String text) {
        return StringUtils.truncate(text, MAX_ERROR_LENGTH);
    }

    private static DomainProblem pendingProblem(final boolean caa, final LocalDateTime approvedAt, final LocalDateTime now) {
        if (caa) {
            return DomainProblem.CAA_BLOCKED;
        }
        return approvedAt == null || !now.isBefore(approvedAt.plus(DNS_GRACE)) ? DomainProblem.DNS_NOT_FOUND : null;
    }

    private static boolean timedOut(final String sslStatus) {
        return EXPIRED.equals(sslStatus) || Strings.CS.endsWith(sslStatus, TIMED_OUT_SUFFIX);
    }

    private static boolean known(final String status) {
        return ACTIVE.equals(status) || ACTIVE_REDEPLOYING.equals(status) || BLOCKED.contains(status) || MOVED_AWAY.contains(status)
                || status.startsWith("pending") || status.startsWith("test_") || "provisioned".equals(status);
    }

    private static String lastErrorOf(final CloudflareHostname hostname) {
        return cut(ObjectUtils.firstNonNull(first(hostname.verificationErrors()), first(hostname.validationErrors())));
    }

    private static String first(final List<String> errors) {
        return errors.stream().filter(StringUtils::isNotBlank).findFirst().orElse(null);
    }

    public record Transition(CustomDomainStatus status, DomainProblem problem, String lastError) {
    }

}
