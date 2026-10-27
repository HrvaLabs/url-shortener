package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByPerformedAtDesc(Pageable pageable);
    Page<AuditLog> findAllByActionOrderByPerformedAtDesc(AuditAction action, Pageable pageable);

    @Modifying
    @Transactional
    @Query("delete from AuditLog a where a.performedAt < :cutoff")
    int deleteByPerformedAtBefore(@Param("cutoff") LocalDateTime cutoff);

}
