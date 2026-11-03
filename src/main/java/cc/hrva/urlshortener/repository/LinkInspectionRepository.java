package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.LinkInspection;
import cc.hrva.urlshortener.model.enums.InspectionState;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkInspectionRepository extends JpaRepository<LinkInspection, Long> {

    Optional<LinkInspection> findFirstByScreenshotStateOrderByScreenshotRequestedAtAscUrlIdAsc(InspectionState screenshotState);

    @Modifying(flushAutomatically = true)
    @Query("update LinkInspection i set i.screenshotState = cc.hrva.urlshortener.model.enums.InspectionState.DONE, i.screenshotTakenAt = :takenAt, "
            + "i.screenshotUrl = :screenshotUrl, i.pageTitle = :pageTitle, i.pageStatus = :pageStatus, i.screenshotFailure = null "
            + "where i.urlId = :urlId and i.screenshotState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING "
            + "and i.screenshotRequestedAt = :requestedAt")
    int recordTaken(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt, @Param("takenAt") LocalDateTime takenAt,
                    @Param("screenshotUrl") String screenshotUrl, @Param("pageTitle") String pageTitle, @Param("pageStatus") Integer pageStatus);

    @Modifying(flushAutomatically = true)
    @Query("update LinkInspection i set i.screenshotState = :state, i.screenshotFailure = :failure "
            + "where i.urlId = :urlId and i.screenshotState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING "
            + "and i.screenshotRequestedAt = :requestedAt")
    int recordOutcome(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt, @Param("state") InspectionState state,
                      @Param("failure") String failure);

    @Modifying(flushAutomatically = true)
    @Query("update LinkInspection i set i.screenshotState = cc.hrva.urlshortener.model.enums.InspectionState.EXPIRED "
            + "where i.screenshotState = cc.hrva.urlshortener.model.enums.InspectionState.DONE and i.screenshotTakenAt < :cutoff")
    int expireTakenBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying(flushAutomatically = true)
    @Query("delete from LinkInspection i where i.urlId = :urlId")
    int deleteByUrlId(@Param("urlId") long urlId);

}
