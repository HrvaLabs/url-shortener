package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.DnsRecord;
import cc.hrva.urlshortener.dto.domain.DomainSteps;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomDomainResponsesTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
    private static final String DNS_NOT_FOUND = "We can't see the CNAME record yet. DNS changes can take up to an hour.";

    private AppProperties appProperties;
    private CustomDomainResponses customDomainResponses;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        customDomainResponses = new CustomDomainResponses(appProperties);
    }

    @Test
    void anApplicationHasNoRecordOrStepsYet() {
        final var response = customDomainResponses.of(domain(CustomDomainStatus.PENDING_REVIEW).build(), 0, CLOCK);

        assertThat(response.dnsRecord()).isNull();
        assertThat(response.steps()).isNull();
        assertThat(response.problem()).isNull();
        assertThat(response.problemText()).isNull();
        assertThat(response.disabledNote()).isNull();
    }

    @Test
    void fromApprovalOnTheRecordAndStepsAreShown() {
        for (final var status : EnumSet.of(CustomDomainStatus.APPROVED_PROVISIONING, CustomDomainStatus.AWAITING_DNS,
                CustomDomainStatus.ACTIVE, CustomDomainStatus.DISABLED, CustomDomainStatus.ERROR)) {
            final var response = customDomainResponses.of(domain(status).reviewedAt(NOW.minusHours(1)).build(), 2, CLOCK);

            assertThat(response.dnsRecord()).isEqualTo(new DnsRecord("CNAME", "go.example.com", "domains.hrva.cc"));
            assertThat(response.steps()).isEqualTo(new DomainSteps(false, false));
            assertThat(response.links()).isEqualTo(2L);
        }
    }

    @Test
    void rejectedAndDeletedDomainsShowNoRecord() {
        for (final var status : EnumSet.of(CustomDomainStatus.REJECTED, CustomDomainStatus.DELETED)) {
            final var response = customDomainResponses.of(domain(status).reviewedAt(NOW.minusHours(1)).build(), 0, CLOCK);

            assertThat(response.dnsRecord()).isNull();
            assertThat(response.steps()).isNull();
        }
    }

    @Test
    void theRecordPointsAtTheConfiguredTarget() {
        appProperties.getDomains().setCnameTarget("Edge.Example.NET.");

        final var response = customDomainResponses.of(domain(CustomDomainStatus.AWAITING_DNS).build(), 0, CLOCK);

        assertThat(response.dnsRecord().target()).isEqualTo("edge.example.net");
    }

    @Test
    void stepsFollowCloudflaresStatuses() {
        final var response = customDomainResponses.of(domain(CustomDomainStatus.AWAITING_DNS)
                .cloudflareStatus("active").cloudflareSslStatus("pending_validation").build(), 0, CLOCK);
        final var issued = customDomainResponses.of(domain(CustomDomainStatus.ACTIVE)
                .cloudflareStatus("active").cloudflareSslStatus("active").build(), 0, CLOCK);

        assertThat(response.steps()).isEqualTo(new DomainSteps(true, false));
        assertThat(issued.steps()).isEqualTo(new DomainSteps(true, true));
    }

    @Test
    void aMissingCnameIsShownOnlyTenMinutesAfterApproval() {
        final var early = customDomainResponses.of(domain(CustomDomainStatus.AWAITING_DNS)
                .reviewedAt(NOW.minusMinutes(9).minusSeconds(59)).problem(DomainProblem.DNS_NOT_FOUND).build(), 0, CLOCK);
        final var due = customDomainResponses.of(domain(CustomDomainStatus.AWAITING_DNS)
                .reviewedAt(NOW.minusMinutes(10)).problem(DomainProblem.DNS_NOT_FOUND).build(), 0, CLOCK);

        assertThat(early.problem()).isNull();
        assertThat(early.problemText()).isNull();
        assertThat(due.problem()).isEqualTo(DomainProblem.DNS_NOT_FOUND);
        assertThat(due.problemText()).isEqualTo(DNS_NOT_FOUND);
    }

    @Test
    void otherProblemsCarryTheirText() {
        final var response = customDomainResponses.of(domain(CustomDomainStatus.ERROR)
                .reviewedAt(NOW.minusMinutes(1)).problem(DomainProblem.CNAME_REMOVED).build(), 0, CLOCK);

        assertThat(response.problem()).isEqualTo(DomainProblem.CNAME_REMOVED);
        assertThat(response.problemText()).isEqualTo(DomainProblems.text(DomainProblem.CNAME_REMOVED));
    }

    @Test
    void aTurnedOffDomainExplainsWhy() {
        assertThat(disabledNote(DomainDisableReason.ADMIN, "Phishing")).isEqualTo("Phishing");
        assertThat(disabledNote(DomainDisableReason.USER_BLOCKED, "Account blocked")).isEqualTo("Turned off because the account was blocked.");
        assertThat(disabledNote(DomainDisableReason.DOMAIN_RULE, "Scam wave")).isEqualTo("Turned off because the domain is on a blocklist.");
        assertThat(disabledNote(DomainDisableReason.BLOCKLIST, "HaGeZi")).isEqualTo("Turned off because the domain is on a blocklist.");
    }

    @Test
    void onlyATurnedOffDomainHasADisabledNote() {
        final var response = customDomainResponses.of(domain(CustomDomainStatus.AWAITING_DNS)
                .disabledReason(DomainDisableReason.ADMIN).disabledNote("Old note").build(), 0, CLOCK);

        assertThat(response.disabledNote()).isNull();
    }

    @Test
    void timesAreCutToTheSecond() {
        final var response = customDomainResponses.of(domain(CustomDomainStatus.ACTIVE)
                .createdAt(NOW.withNano(123_456_789))
                .reviewedAt(NOW.plusSeconds(1).withNano(999_000_000))
                .activatedAt(NOW.plusSeconds(2).withNano(1_000))
                .lastCheckedAt(NOW.plusSeconds(3).withNano(500_000_000)).build(), 0, CLOCK);

        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.reviewedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(response.activatedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(response.lastCheckedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    private String disabledNote(final DomainDisableReason reason, final String note) {
        return customDomainResponses.of(domain(CustomDomainStatus.DISABLED).disabledReason(reason).disabledNote(note).build(), 0, CLOCK)
                .disabledNote();
    }

    private static CustomDomain.CustomDomainBuilder domain(final CustomDomainStatus status) {
        return CustomDomain.builder().id(7L).hostname("go.example.com").hostnameKey("go.example.com").status(status)
                .reason("Menu links for my cafe").createdAt(NOW.minusDays(1)).updatedAt(NOW.minusDays(1));
    }

}
