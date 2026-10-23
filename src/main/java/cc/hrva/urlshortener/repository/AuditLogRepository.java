package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByPerformedAtDesc(Pageable pageable);
    Page<AuditLog> findAllByActionOrderByPerformedAtDesc(AuditAction action, Pageable pageable);

}
