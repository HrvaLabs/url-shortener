package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.LinkScreenshot;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkScreenshotRepository extends JpaRepository<LinkScreenshot, Long> {

    @Modifying(flushAutomatically = true)
    @Query("delete from LinkScreenshot s where s.urlId = :urlId")
    int deleteByUrlId(@Param("urlId") long urlId);

    @Modifying(flushAutomatically = true)
    @Query("delete from LinkScreenshot s where s.urlId in (select i.urlId from LinkInspection i where i.screenshotTakenAt < :cutoff)")
    int deleteTakenBefore(@Param("cutoff") LocalDateTime cutoff);

}
