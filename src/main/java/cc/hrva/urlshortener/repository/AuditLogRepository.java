package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import cc.hrva.urlshortener.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    @Modifying
    @Transactional
    @Query("delete from AuditLog a where a.performedAt < :cutoff")
    int deleteByPerformedAtBefore(@Param("cutoff") LocalDateTime cutoff);

}
