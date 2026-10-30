package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.ReportedDomain;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ReportedDomainRepository extends JpaRepository<ReportedDomain, String> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(nativeQuery = true, value = """
            MERGE INTO reported_domain t
            USING (VALUES (CAST(:domain AS VARCHAR(253)), CAST(:registrar AS VARCHAR(255)), CAST(:abuseEmail AS VARCHAR(320)),
                           CAST(:reportedBy AS VARCHAR(255)), CAST(:reportedAt AS TIMESTAMP)))
                  AS s(domain, registrar, abuse_email, reported_by, reported_at)
            ON t.domain = s.domain
            WHEN MATCHED THEN UPDATE SET registrar = COALESCE(s.registrar, t.registrar), abuse_email = COALESCE(s.abuse_email, t.abuse_email)
            WHEN NOT MATCHED THEN INSERT (domain, registrar, abuse_email, reported_by, reported_at)
                 VALUES (s.domain, s.registrar, s.abuse_email, s.reported_by, s.reported_at)
            """)
    int upsert(@Param("domain") String domain, @Param("registrar") String registrar, @Param("abuseEmail") String abuseEmail,
               @Param("reportedBy") String reportedBy, @Param("reportedAt") LocalDateTime reportedAt);

    @Query("select r.domain from ReportedDomain r where r.suspendedAt is null and r.reportedAt >= :since order by r.domain")
    List<String> findToRecheck(@Param("since") LocalDateTime since);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update ReportedDomain r set r.suspendedAt = :now, r.lastCheckedAt = :now where r.domain = :domain and r.suspendedAt is null")
    int markSuspended(@Param("domain") String domain, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update ReportedDomain r set r.lastCheckedAt = :now where r.domain in :domains")
    int markChecked(@Param("domains") Collection<String> domains, @Param("now") LocalDateTime now);

}
