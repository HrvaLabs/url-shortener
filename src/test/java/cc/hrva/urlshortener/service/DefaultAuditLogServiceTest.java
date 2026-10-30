package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AuditLogFilter;
import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAuditLogServiceTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("performedAt"), Sort.Order.desc("id"));

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new DefaultAuditLogService(auditLogRepository);
    }

    @Test
    void aSucceededActionIsStoredWithItsActionAndNotFailed() {
        auditLogService.log("admin@hrva.cc", AuditAction.REVOKE_URL, "Url", "12", "");

        final var saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue())
                .extracting(AuditLog::getPerformedBy, AuditLog::getAction, AuditLog::isFailed, AuditLog::getTargetType, AuditLog::getTargetIdentifier,
                        AuditLog::getDetails)
                .containsExactly("admin@hrva.cc", AuditAction.REVOKE_URL, false, "Url", "12", "");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theLogLineMasksWhoDidItWhileTheRowKeepsTheAddress(final CapturedOutput output) {
        auditLogService.log("ana@hrva.cc", AuditAction.REMOVE_PASSWORD, "User", "700", "method: PASSWORD");

        assertThat(output).contains("Audit: a***@hrva.cc REMOVE_PASSWORD User 700 - method: PASSWORD").doesNotContain("ana@hrva.cc");
        final var saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue().getPerformedBy()).isEqualTo("ana@hrva.cc");
    }

    @Test
    void aFailedActionIsStoredAsItsActionMarkedFailed() {
        auditLogService.logFailure("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "9", "Url with id 9 doesn't exist");

        final var saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue())
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.UPDATE_URL, true, "Url with id 9 doesn't exist");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLogComesNewestFirstWithTheIdBreakingTies() {
        final Page<AuditLog> everything = Page.empty();
        when(auditLogRepository.findAll(any(Specification.class), eq(PageRequest.of(0, 50, NEWEST_FIRST)))).thenReturn(everything);

        assertThat(auditLogService.getAuditLogs(PageRequest.of(0, 50), AuditLogFilter.NONE)).isSameAs(everything);
        assertThat(auditLogService.getAuditLogs(PageRequest.of(0, 50),
                new AuditLogFilter(AuditAction.RESOLVE_REVIEW, "rhea", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 29)))).isSameAs(everything);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLogCanBeReadOldestFirst() {
        final var oldestFirst = PageRequest.of(1, 50, Sort.by(Sort.Order.asc("performedAt"), Sort.Order.desc("id")));
        when(auditLogRepository.findAll(any(Specification.class), eq(oldestFirst))).thenReturn(Page.empty());

        auditLogService.getAuditLogs(PageRequest.of(1, 50, Sort.by(Sort.Order.asc("performedAt"))), AuditLogFilter.NONE);

        verify(auditLogRepository).findAll(any(Specification.class), eq(oldestFirst));
    }

    @Test
    void theLogRefusesToSortByAFieldOffTheList() {
        assertThatThrownBy(() -> auditLogService.getAuditLogs(PageRequest.of(0, 50, Sort.by("createDate")), AuditLogFilter.NONE))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be performedAt");
        verifyNoInteractions(auditLogRepository);
    }

}
