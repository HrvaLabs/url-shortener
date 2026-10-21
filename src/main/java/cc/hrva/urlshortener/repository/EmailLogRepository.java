package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import cc.hrva.urlshortener.model.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    Page<EmailLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<EmailLog> findByRecipientContainingIgnoreCase(String recipient);
    List<EmailLog> findAllByStatusAndCreatedAtBefore(String status, LocalDateTime createdBefore);
    List<EmailLog> findAllByBodyIsNotNullAndCreatedAtBefore(LocalDateTime createdBefore);

}
