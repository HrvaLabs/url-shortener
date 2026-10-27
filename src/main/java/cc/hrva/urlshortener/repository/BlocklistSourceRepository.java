package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.SourceKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BlocklistSourceRepository extends JpaRepository<BlocklistSource, Long> {

    List<BlocklistSource> findByEnabledTrue();

    @Modifying
    @Transactional
    @Query("update BlocklistSource s set s.lastFetchedAt = :fetchedAt, s.lastError = :lastError "
            + "where s.id = :id and s.enabled = true and s.kind = :kind")
    int recordRefresh(@Param("id") long id, @Param("kind") SourceKind kind, @Param("fetchedAt") LocalDateTime fetchedAt,
            @Param("lastError") String lastError);

    @Modifying
    @Transactional
    @Query("update BlocklistSource s set s.lastFetchedAt = :fetchedAt, s.lastError = :lastError, s.lastSuccessAt = :fetchedAt, "
            + "s.lastSuccessUrl = :successUrl, s.domainCount = :domainCount, s.listVersion = :listVersion, s.etag = :etag, "
            + "s.lastModified = :lastModified "
            + "where s.id = :id and s.enabled = true and s.kind = :kind")
    int recordLoadedList(@Param("id") long id, @Param("kind") SourceKind kind, @Param("fetchedAt") LocalDateTime fetchedAt,
            @Param("lastError") String lastError, @Param("successUrl") String successUrl, @Param("domainCount") long domainCount,
            @Param("listVersion") String listVersion, @Param("etag") String etag, @Param("lastModified") String lastModified);

}
