package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import cc.hrva.urlshortener.model.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long>, JpaSpecificationExecutor<EmailLog> {

    List<EmailLog> findByRecipientContainingIgnoreCase(String recipient);
    List<EmailLog> findAllByStatusAndCreatedAtBefore(String status, LocalDateTime createdBefore);
    List<EmailLog> findAllByBodyIsNotNullAndCreatedAtBefore(LocalDateTime createdBefore);

    @Modifying
    @Transactional
    @Query("delete from EmailLog e where e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

}
