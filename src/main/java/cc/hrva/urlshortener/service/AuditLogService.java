package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLogService {

    void log(String performedBy, AuditAction action, String targetType, String targetIdentifier, String details);
    void logFailure(String performedBy, AuditAction action, String targetType, String targetIdentifier, String details);
    Page<AuditLog> getAuditLogs(Pageable pageable, AuditAction action);

}
