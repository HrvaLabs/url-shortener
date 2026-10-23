package cc.hrva.urlshortener.service;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAuditLogServiceTest {

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
    void aFailedActionIsStoredAsItsActionMarkedFailed() {
        auditLogService.logFailure("admin@hrva.cc", AuditAction.UPDATE_URL, "Url", "9", "Url with id 9 doesn't exist");

        final var saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue())
                .extracting(AuditLog::getAction, AuditLog::isFailed, AuditLog::getDetails)
                .containsExactly(AuditAction.UPDATE_URL, true, "Url with id 9 doesn't exist");
    }

    @Test
    void theLogListsEveryActionWithoutAFilterAndOnlyThatActionWithOne() {
        final var pageable = PageRequest.of(0, 50);
        final Page<AuditLog> everything = Page.empty();
        final Page<AuditLog> resolved = Page.empty();
        when(auditLogRepository.findAllByOrderByPerformedAtDesc(pageable)).thenReturn(everything);
        when(auditLogRepository.findAllByActionOrderByPerformedAtDesc(AuditAction.RESOLVE_REVIEW, pageable)).thenReturn(resolved);

        assertThat(auditLogService.getAuditLogs(pageable, null)).isSameAs(everything);
        assertThat(auditLogService.getAuditLogs(pageable, AuditAction.RESOLVE_REVIEW)).isSameAs(resolved);
    }

}
