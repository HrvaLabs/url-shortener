package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.LinkInspection;
import cc.hrva.urlshortener.model.enums.InspectionState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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
    @Query("update LinkInspection i set i.screenshotTakenAt = null, i.screenshotUrl = null, i.pageTitle = null, i.pageStatus = null "
            + "where i.screenshotState in (cc.hrva.urlshortener.model.enums.InspectionState.PENDING, cc.hrva.urlshortener.model.enums.InspectionState.FAILED) "
            + "and i.screenshotTakenAt < :cutoff")
    int forgetTakenBefore(@Param("cutoff") LocalDateTime cutoff);

    @Query("select i from LinkInspection i, Url u where u.id = i.urlId "
            + "and i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING and i.vtRequestedAt <= :now "
            + "and (u.chainCheckedAt is not null or i.vtSubmittedAt is not null or i.vtRequestedAt <= :chainDeadline) "
            + "order by i.vtRequestedAt asc, i.urlId asc")
    List<LinkInspection> findDueOnVirusTotal(@Param("now") LocalDateTime now, @Param("chainDeadline") LocalDateTime chainDeadline, Limit limit);

    @Modifying(flushAutomatically = true)
    @Query("update LinkInspection i set i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.DONE, i.vtCheckedAt = :checkedAt, "
            + "i.vtUrl = :checkedUrl, i.vtReportId = :reportId, i.vtMalicious = :malicious, i.vtSuspicious = :suspicious, i.vtHarmless = :harmless, "
            + "i.vtUndetected = :undetected, i.vtAnalysedAt = :analysedAt, i.vtFailure = null "
            + "where i.urlId = :urlId and i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING and i.vtRequestedAt = :requestedAt")
    int recordVirusTotalFound(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt, @Param("checkedAt") LocalDateTime checkedAt,
                              @Param("checkedUrl") String checkedUrl, @Param("reportId") String reportId, @Param("malicious") Integer malicious,
                              @Param("suspicious") Integer suspicious, @Param("harmless") Integer harmless, @Param("undetected") Integer undetected,
                              @Param("analysedAt") LocalDateTime analysedAt);

    @Modifying(flushAutomatically = true)
    @Query("update LinkInspection i set i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.NOT_FOUND, i.vtCheckedAt = :checkedAt, "
            + "i.vtUrl = :checkedUrl, i.vtReportId = null, i.vtMalicious = null, i.vtSuspicious = null, i.vtHarmless = null, i.vtUndetected = null, "
            + "i.vtAnalysedAt = null, i.vtFailure = null "
            + "where i.urlId = :urlId and i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING and i.vtRequestedAt = :requestedAt")
    int recordVirusTotalNotFound(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt, @Param("checkedAt") LocalDateTime checkedAt,
                                 @Param("checkedUrl") String checkedUrl);

    @Modifying(flushAutomatically = true)
    @Query("update LinkInspection i set i.vtState = :state, i.vtFailure = :failure "
            + "where i.urlId = :urlId and i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING and i.vtRequestedAt = :requestedAt")
    int recordVirusTotalOutcome(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt, @Param("state") InspectionState state,
                                @Param("failure") String failure);

    @Modifying(flushAutomatically = true)
    @Query("update LinkInspection i set i.vtNextCandidate = :nextCandidate, i.vtFailure = null "
            + "where i.urlId = :urlId and i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING and i.vtRequestedAt = :requestedAt")
    int recordVirusTotalNextCandidate(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt,
                                      @Param("nextCandidate") int nextCandidate);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LinkInspection i set i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING, i.vtRequestedAt = :requestedAt, "
            + "i.vtSubmittedAt = :submittedAt, i.vtSubmittedId = :submittedId, i.vtNextCandidate = null, i.vtFailure = null where i.urlId = :urlId")
    int queueVirusTotalAfterSubmit(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt,
                                   @Param("submittedAt") LocalDateTime submittedAt, @Param("submittedId") String submittedId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LinkInspection i set i.vtState = cc.hrva.urlshortener.model.enums.InspectionState.PENDING, i.vtRequestedAt = :requestedAt, "
            + "i.vtSubmittedAt = null, i.vtSubmittedId = null, i.vtNextCandidate = null, i.vtFailure = null where i.urlId = :urlId")
    int queueVirusTotalLookup(@Param("urlId") long urlId, @Param("requestedAt") LocalDateTime requestedAt);

    @Modifying(flushAutomatically = true)
    @Query("delete from LinkInspection i where i.urlId = :urlId")
    int deleteByUrlId(@Param("urlId") long urlId);

}
