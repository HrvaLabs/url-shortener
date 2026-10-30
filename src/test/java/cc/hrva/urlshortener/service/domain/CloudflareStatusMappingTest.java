package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareHostname;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.service.domain.CloudflareStatusMapping.Transition;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareStatusMappingTest {

    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 29, 9, 0);
    private static final LocalDateTime LATER = APPROVED_AT.plusHours(2);
    private static final String CAA_ERROR = "CAA record for example.com prevents issuance";

    @Test
    void anActiveHostnameWithAnActiveCertificateIsActive() {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("active", "active")))
                .isEqualTo(new Transition(CustomDomainStatus.ACTIVE, null, null));
    }

    @Test
    void anActiveHostnameWhoseCertificateIsStillComingAwaitsDns() {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("active", "pending_validation")))
                .isEqualTo(new Transition(CustomDomainStatus.AWAITING_DNS, null, null));
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("active", "pending_issuance", List.of(), List.of(CAA_ERROR))))
                .isEqualTo(new Transition(CustomDomainStatus.AWAITING_DNS, DomainProblem.CAA_BLOCKED, CAA_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "pending_provisioned", "provisioned", "active_redeploying", "test_pending", "test_active",
            "test_active_apex", "test_failed", "pending_migration"})
    void aHostnameStillPendingAwaitsDnsAndSaysSoAfterTenMinutes(final String status) {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname(status, "pending_validation")))
                .isEqualTo(new Transition(CustomDomainStatus.AWAITING_DNS, DomainProblem.DNS_NOT_FOUND, null));
        assertThat(CloudflareStatusMapping.next(CustomDomainStatus.AWAITING_DNS, false, hostname(status, "pending_validation"), APPROVED_AT,
                APPROVED_AT.plusMinutes(5))).isEqualTo(new Transition(CustomDomainStatus.AWAITING_DNS, null, null));
    }

    @Test
    void dnsNotFoundOnlyAfterTenMinutes() {
        final var pending = hostname("pending", "initializing");

        assertThat(CloudflareStatusMapping.next(CustomDomainStatus.AWAITING_DNS, false, pending, APPROVED_AT, APPROVED_AT.plusMinutes(10).minusSeconds(1))
                .problem()).isNull();
        assertThat(CloudflareStatusMapping.next(CustomDomainStatus.AWAITING_DNS, false, pending, APPROVED_AT, APPROVED_AT.plusMinutes(10))
                .problem()).isEqualTo(DomainProblem.DNS_NOT_FOUND);
    }

    @Test
    void activeRedeployingKeepsActive() {
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname("active_redeploying", "active")))
                .isEqualTo(new Transition(CustomDomainStatus.ACTIVE, null, null));
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("active_redeploying", "active")).status())
                .isEqualTo(CustomDomainStatus.AWAITING_DNS);
    }

    @Test
    void aStatusCloudflareAddsLaterNeverTakesAnActiveDomainDown() {
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname("something_new", "active")))
                .isEqualTo(new Transition(CustomDomainStatus.ACTIVE, null, null));
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname(null, null)))
                .isEqualTo(new Transition(CustomDomainStatus.AWAITING_DNS, DomainProblem.DNS_NOT_FOUND, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"initializing_timed_out", "validation_timed_out", "issuance_timed_out", "deployment_timed_out", "expired"})
    void aCertificateThatTimedOutIsAnError(final String sslStatus) {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("pending", sslStatus)))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CERTIFICATE_TIMED_OUT, null));
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("active", sslStatus)))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CERTIFICATE_TIMED_OUT, null));
        assertThat(next(CustomDomainStatus.ERROR, true, hostname("active", sslStatus)))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CERTIFICATE_TIMED_OUT, null));
    }

    @ParameterizedTest
    @CsvSource({"active,pending_validation", "active,pending_issuance", "active,pending_deployment", "active,initializing",
            "active,staging_deployment", "active,validation_timed_out", "active,issuance_timed_out", "active,expired", "active,pending_expiration",
            "active_redeploying,expired", "something_new,validation_timed_out"})
    void anActiveDomainKeepsServingWhileOnlyItsCertificateIsNotActive(final String status, final String sslStatus) {
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname(status, sslStatus)))
                .isEqualTo(new Transition(CustomDomainStatus.ACTIVE, null, "Certificate status in Cloudflare: " + sslStatus));
    }

    @Test
    void anActiveDomainWhoseCertificateIsRenewingKeepsCloudflaresMessageForAdmins() {
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname("active", "pending_validation", List.of(), List.of(CAA_ERROR))))
                .isEqualTo(new Transition(CustomDomainStatus.ACTIVE, null, CAA_ERROR));
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname("active", null)))
                .isEqualTo(new Transition(CustomDomainStatus.ACTIVE, null, "Certificate status in Cloudflare: unknown"));
    }

    @Test
    void anActiveDomainStillLeavesActiveForAHostnameProblem() {
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname("moved", "pending_validation")).problem()).isEqualTo(DomainProblem.CNAME_REMOVED);
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname("blocked", "expired")).problem()).isEqualTo(DomainProblem.CLOUDFLARE_BLOCKED);
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname("pending", "expired")).status()).isEqualTo(CustomDomainStatus.ERROR);
    }

    @Test
    void aCertificateThatTimedOutOverCaaIsCaaBlocked() {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("active", "validation_timed_out", List.of(), List.of(CAA_ERROR))))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CAA_BLOCKED, CAA_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {"moved", "deleted", "pending_deletion"})
    void aHostnameMovedAwayAfterItWasActiveIsCnameRemoved(final String status) {
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname(status, "active")))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CNAME_REMOVED, null));
        assertThat(next(CustomDomainStatus.ERROR, true, hostname(status, "active")))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CNAME_REMOVED, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"moved", "deleted", "pending_deletion"})
    void aHostnameMovedAwayBeforeItWasEverActiveAwaitsDns(final String status) {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname(status, "pending_validation")))
                .isEqualTo(new Transition(CustomDomainStatus.AWAITING_DNS, DomainProblem.DNS_NOT_FOUND, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "pending_blocked"})
    void aBlockedHostnameIsCloudflareBlocked(final String status) {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname(status, "pending_validation")))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CLOUDFLARE_BLOCKED, null));
        assertThat(next(CustomDomainStatus.ACTIVE, true, hostname(status, "validation_timed_out")))
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.CLOUDFLARE_BLOCKED, null));
    }

    @Test
    void aHostnameGoneFromCloudflareIsProvisioningFailed() {
        assertThat(CloudflareStatusMapping.gone())
                .isEqualTo(new Transition(CustomDomainStatus.ERROR, DomainProblem.PROVISIONING_FAILED, "Cloudflare no longer has this hostname"));
    }

    @Test
    void caaIsDetectedInValidationErrors() {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("pending", "pending_validation", List.of(),
                List.of("Some other problem", CAA_ERROR))).problem()).isEqualTo(DomainProblem.CAA_BLOCKED);
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("pending", "pending_validation",
                List.of("custom hostname does not CNAME to this zone."), List.of())).problem()).isEqualTo(DomainProblem.DNS_NOT_FOUND);
    }

    @Test
    void lastErrorPrefersVerificationErrors() {
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("pending", "pending_validation",
                List.of("custom hostname does not CNAME to this zone.", "second"), List.of(CAA_ERROR))).lastError())
                .isEqualTo("custom hostname does not CNAME to this zone.");
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("pending", "pending_validation", List.of(), List.of(CAA_ERROR)))
                .lastError()).isEqualTo(CAA_ERROR);
        assertThat(next(CustomDomainStatus.AWAITING_DNS, false, hostname("active", "active")).lastError()).isNull();
    }

    @Test
    void lastErrorIsCutAt500() {
        final var transition = next(CustomDomainStatus.AWAITING_DNS, false, hostname("pending", "pending_validation", List.of("x".repeat(600)), List.of()));

        assertThat(transition.lastError()).hasSize(500).isEqualTo("x".repeat(500));
        assertThat(CloudflareStatusMapping.cut(null)).isNull();
        assertThat(CloudflareStatusMapping.cut("short")).isEqualTo("short");
    }

    @Test
    void aRefusalIsDescribedByCloudflaresMessage() {
        assertThat(CloudflareStatusMapping.failureText(new CloudflareResult.Refused<>(409, 1406, "Duplicate custom hostname found.")))
                .isEqualTo("Duplicate custom hostname found.");
        assertThat(CloudflareStatusMapping.failureText(new CloudflareResult.Refused<>(403, 10000, null)))
                .isEqualTo("Cloudflare refused the request (HTTP 403, code 10000)");
        assertThat(CloudflareStatusMapping.failureText(new CloudflareResult.Refused<>(400, 1, "y".repeat(700)))).hasSize(500);
    }

    @Test
    void anUnreachableCloudflareIsDescribedInWords() {
        assertThat(CloudflareStatusMapping.failureText(new CloudflareResult.Unreachable<>("timeout")))
                .isEqualTo("Cloudflare couldn't be reached (timeout)");
        assertThat(CloudflareStatusMapping.failureText(new CloudflareResult.NotFound<>())).isEqualTo("Cloudflare answered 404");
    }

    @ParameterizedTest
    @EnumSource(CustomDomainStatus.class)
    void theMappingNeverAnswersWithoutAStatus(final CustomDomainStatus current) {
        assertThat(next(current, false, hostname("pending", "pending_validation")).status()).isNotNull();
    }

    private static Transition next(final CustomDomainStatus current, final boolean wasActive, final CloudflareHostname hostname) {
        return CloudflareStatusMapping.next(current, wasActive, hostname, APPROVED_AT, LATER);
    }

    private static CloudflareHostname hostname(final String status, final String sslStatus) {
        return hostname(status, sslStatus, List.of(), List.of());
    }

    private static CloudflareHostname hostname(final String status, final String sslStatus, final List<String> verificationErrors,
                                               final List<String> validationErrors) {
        return new CloudflareHostname("cf-1", "go.example.com", status, sslStatus, verificationErrors, validationErrors);
    }

}
