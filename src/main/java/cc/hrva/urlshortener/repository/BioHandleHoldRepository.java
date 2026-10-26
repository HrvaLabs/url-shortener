package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import cc.hrva.urlshortener.model.BioHandleHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface BioHandleHoldRepository extends JpaRepository<BioHandleHold, String> {

    Optional<BioHandleHold> findByHandleAndHeldUntilAfter(String handle, LocalDateTime now);

    @Transactional
    long deleteByHeldUntilBefore(LocalDateTime now);

}
