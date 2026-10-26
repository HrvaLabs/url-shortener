package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import cc.hrva.urlshortener.model.BioHandleHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface BioHandleHoldRepository extends JpaRepository<BioHandleHold, String> {

    Optional<BioHandleHold> findByHandleAndHeldUntilAfter(String handle, LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from BioHandleHold h where h.heldUntil < :now")
    long deleteByHeldUntilBefore(@Param("now") LocalDateTime now);

}
