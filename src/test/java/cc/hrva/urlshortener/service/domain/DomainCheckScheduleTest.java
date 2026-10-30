package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DomainCheckScheduleTest {

    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 29, 9, 0);

    @Test
    void aWaitingDomainIsCheckedEveryMinuteInItsFirstHour() {
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofMinutes(59))).isEqualTo(after(Duration.ofMinutes(59), Duration.ofMinutes(1)));
        assertThat(waiting(null, Duration.ofMinutes(1))).isEqualTo(after(Duration.ofMinutes(1), Duration.ofMinutes(1)));
    }

    @Test
    void thenEveryTenMinutesUntilADayHasPassed() {
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofHours(1))).isEqualTo(after(Duration.ofHours(1), Duration.ofMinutes(10)));
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofHours(2))).isEqualTo(after(Duration.ofHours(2), Duration.ofMinutes(10)));
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofHours(24).minusMinutes(1)))
                .isEqualTo(after(Duration.ofHours(24).minusMinutes(1), Duration.ofMinutes(10)));
    }

    @Test
    void thenHourlyUntilFourteenDays() {
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofDays(2))).isEqualTo(after(Duration.ofDays(2), Duration.ofHours(1)));
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofDays(14).minusMinutes(1)))
                .isEqualTo(after(Duration.ofDays(14).minusMinutes(1), Duration.ofHours(1)));
    }

    @Test
    void afterFourteenDaysWithoutTheCnameTheChecksStop() {
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofDays(14).plusMinutes(1))).isNull();
        assertThat(waiting(DomainProblem.DNS_NOT_FOUND, Duration.ofDays(14))).isNull();
    }

    @Test
    void afterFourteenDaysADomainWhoseCnameWasSeenIsStillCheckedHourly() {
        assertThat(waiting(null, Duration.ofDays(14).plusMinutes(1))).isEqualTo(after(Duration.ofDays(14).plusMinutes(1), Duration.ofHours(1)));
        assertThat(waiting(DomainProblem.CAA_BLOCKED, Duration.ofDays(20))).isEqualTo(after(Duration.ofDays(20), Duration.ofHours(1)));
    }

    @Test
    void aFailedCreateIsRetriedAfterOneFiveAndThirtyMinutesThenHourly() {
        assertThat(provisioning(1, Duration.ZERO)).isEqualTo(after(Duration.ZERO, Duration.ofMinutes(1)));
        assertThat(provisioning(2, Duration.ofMinutes(1))).isEqualTo(after(Duration.ofMinutes(1), Duration.ofMinutes(5)));
        assertThat(provisioning(3, Duration.ofMinutes(6))).isEqualTo(after(Duration.ofMinutes(6), Duration.ofMinutes(30)));
        assertThat(provisioning(4, Duration.ofMinutes(36))).isEqualTo(after(Duration.ofMinutes(36), Duration.ofHours(1)));
        assertThat(provisioning(20, Duration.ofHours(17))).isEqualTo(after(Duration.ofHours(17), Duration.ofHours(1)));
        assertThat(provisioning(0, Duration.ZERO)).isEqualTo(after(Duration.ZERO, Duration.ofMinutes(1)));
    }

    @Test
    void aFailedCreateStopsBeingRetriedAfterADay() {
        assertThat(provisioning(25, Duration.ofHours(24).minusMinutes(1))).isEqualTo(after(Duration.ofHours(24).minusMinutes(1), Duration.ofHours(1)));
        assertThat(provisioning(26, Duration.ofHours(24))).isNull();
        assertThat(provisioning(1, Duration.ofDays(3))).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DomainProblem.class, names = {"CERTIFICATE_TIMED_OUT", "CNAME_REMOVED", "CAA_BLOCKED", "CLOUDFLARE_BLOCKED"})
    void anErrorThatCanClearByItselfIsCheckedHourly(final DomainProblem problem) {
        final var now = APPROVED_AT.plusDays(30);

        assertThat(DomainCheckSchedule.nextCheck(CustomDomainStatus.ERROR, problem, APPROVED_AT, 500, now)).isEqualTo(now.plusHours(1));
    }

    @ParameterizedTest
    @EnumSource(value = DomainProblem.class, names = {"PROVISIONING_FAILED", "DNS_TIMED_OUT", "DNS_NOT_FOUND"})
    void anErrorThatWaitsForCheckNowOrRetryIsNotChecked(final DomainProblem problem) {
        assertThat(DomainCheckSchedule.nextCheck(CustomDomainStatus.ERROR, problem, APPROVED_AT, 3, APPROVED_AT.plusDays(15))).isNull();
        assertThat(DomainCheckSchedule.nextCheck(CustomDomainStatus.ERROR, null, APPROVED_AT, 3, APPROVED_AT.plusDays(15))).isNull();
    }

    @Test
    void anActiveDomainIsCheckedDaily() {
        final var now = APPROVED_AT.plusDays(3);

        assertThat(DomainCheckSchedule.nextCheck(CustomDomainStatus.ACTIVE, null, APPROVED_AT, 40, now)).isEqualTo(now.plusDays(1));
        assertThat(DomainCheckSchedule.nextCheck(CustomDomainStatus.ACTIVE, null, APPROVED_AT, 40, APPROVED_AT.plusDays(400)))
                .isEqualTo(APPROVED_AT.plusDays(401));
    }

    @ParameterizedTest
    @EnumSource(value = CustomDomainStatus.class, names = {"PENDING_REVIEW", "REJECTED", "DISABLED", "DELETED"})
    void aDomainNobodyWaitsOnIsNotChecked(final CustomDomainStatus status) {
        assertThat(DomainCheckSchedule.nextCheck(status, null, APPROVED_AT, 0, APPROVED_AT.plusMinutes(5))).isNull();
    }

    @Test
    void aDomainWithoutAnApprovalTimeCountsFromNow() {
        final var now = APPROVED_AT.plusDays(20);

        assertThat(DomainCheckSchedule.nextCheck(CustomDomainStatus.AWAITING_DNS, DomainProblem.DNS_NOT_FOUND, null, 0, now)).isEqualTo(now.plusMinutes(1));
    }

    private static LocalDateTime waiting(final DomainProblem problem, final Duration sinceApproval) {
        return DomainCheckSchedule.nextCheck(CustomDomainStatus.AWAITING_DNS, problem, APPROVED_AT, 7, APPROVED_AT.plus(sinceApproval));
    }

    private static LocalDateTime provisioning(final int attempts, final Duration sinceApproval) {
        return DomainCheckSchedule.nextCheck(CustomDomainStatus.APPROVED_PROVISIONING, DomainProblem.PROVISIONING_FAILED, APPROVED_AT, attempts,
                APPROVED_AT.plus(sinceApproval));
    }

    private static LocalDateTime after(final Duration sinceApproval, final Duration wait) {
        return APPROVED_AT.plus(sinceApproval).plus(wait);
    }

}
