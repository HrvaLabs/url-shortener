package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface CustomDomainRepository extends JpaRepository<CustomDomain, Long>, JpaSpecificationExecutor<CustomDomain> {

    @Override
    @EntityGraph(attributePaths = "owner", type = EntityGraph.EntityGraphType.LOAD)
    Page<CustomDomain> findAll(Specification<CustomDomain> spec, Pageable pageable);

    Optional<CustomDomain> findByIdAndOwnerId(Long id, Long ownerId);

    List<CustomDomain> findByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);

    long countByOwnerIdAndStatusIn(Long ownerId, Collection<CustomDomainStatus> statuses);

    Optional<CustomDomain> findByHostnameKey(String hostnameKey);

    List<CustomDomain> findByStatusIn(Collection<CustomDomainStatus> statuses);

    long countByStatus(CustomDomainStatus status);

    @Query("select new cc.hrva.urlshortener.repository.OwnerLinkCount(u.owner.id, count(u), sum(case when u.blockReason is not null then 1 else 0 end))"
            + " from Url u where u.owner.id in :ownerIds group by u.owner.id")
    List<OwnerLinkCount> countLinksOfOwners(@Param("ownerIds") Collection<Long> ownerIds);

    @Query("select new cc.hrva.urlshortener.repository.OwnerReviewCount(r.url.owner.id, count(distinct r.url.id))"
            + " from LinkReview r where r.resolvedAt is null and r.url.owner.id in :ownerIds group by r.url.owner.id")
    List<OwnerReviewCount> countOpenReviewsOfOwners(@Param("ownerIds") Collection<Long> ownerIds);

    @Query("select new cc.hrva.urlshortener.repository.DomainLinkCount(u.domain.id, count(u), coalesce(sum(u.visits), 0L))"
            + " from Url u where u.domain.id in :domainIds group by u.domain.id")
    List<DomainLinkCount> countLinksOfDomains(@Param("domainIds") Collection<Long> domainIds);

    @Query("""
            select count(d) from CustomDomain d
            where d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.APPROVED_PROVISIONING
               or (d.cloudflareId is not null
                   and (d.status <> cc.hrva.urlshortener.model.enums.CustomDomainStatus.DELETED or d.cloudflareDeletePending = true))""")
    long countSlotsInUse();

    @Query("select d from CustomDomain d left join fetch d.owner where d.id = :id")
    Optional<CustomDomain> findWithOwner(@Param("id") long id);

    @Query("""
            select d.id from CustomDomain d
            where d.status = :status
              and ((d.nextCheckAt is null and d.status <> cc.hrva.urlshortener.model.enums.CustomDomainStatus.ERROR) or d.nextCheckAt <= :now)
            order by d.nextCheckAt asc nulls first, d.id""")
    List<Long> findDueIds(@Param("status") CustomDomainStatus status, @Param("now") LocalDateTime now, Limit limit);

    @Query("select d.id from CustomDomain d where d.cloudflareDeletePending = true order by d.id")
    List<Long> findDeletePendingIds(Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.APPROVED_PROVISIONING,
                d.reviewedBy = :reviewedBy, d.reviewNote = :note, d.reviewedAt = :now, d.nextCheckAt = :now, d.updatedAt = :now
            where d.id = :id and d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.PENDING_REVIEW""")
    int markApproved(@Param("id") long id, @Param("reviewedBy") String reviewedBy, @Param("note") String note, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.REJECTED, d.hostnameKey = null,
                d.reviewedBy = :reviewedBy, d.reviewNote = :note, d.reviewedAt = :now, d.nextCheckAt = null, d.updatedAt = :now
            where d.id = :id and d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.PENDING_REVIEW""")
    int markRejected(@Param("id") long id, @Param("reviewedBy") String reviewedBy, @Param("note") String note, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.AWAITING_DNS, d.cloudflareId = :cloudflareId,
                d.problem = null, d.lastError = null, d.nextCheckAt = :nextCheckAt, d.updatedAt = :now
            where d.id = :id and d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.APPROVED_PROVISIONING""")
    int recordCreated(@Param("id") long id, @Param("cloudflareId") String cloudflareId, @Param("now") LocalDateTime now,
                      @Param("nextCheckAt") LocalDateTime nextCheckAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = case when cast(:nextCheckAt as LocalDateTime) is null
                                then cc.hrva.urlshortener.model.enums.CustomDomainStatus.ERROR
                                else cc.hrva.urlshortener.model.enums.CustomDomainStatus.APPROVED_PROVISIONING end,
                d.problem = case when cast(:nextCheckAt as LocalDateTime) is null
                                 then cc.hrva.urlshortener.model.enums.DomainProblem.PROVISIONING_FAILED
                                 else d.problem end,
                d.lastError = :error, d.nextCheckAt = :nextCheckAt, d.checkCount = d.checkCount + 1, d.lastCheckedAt = :now, d.updatedAt = :now
            where d.id = :id and d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.APPROVED_PROVISIONING""")
    int recordProvisioningFailure(@Param("id") long id, @Param("error") String error, @Param("nextCheckAt") LocalDateTime nextCheckAt,
                                  @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = :newStatus, d.cloudflareStatus = :cloudflareStatus, d.cloudflareSslStatus = :sslStatus, d.problem = :problem,
                d.lastError = :lastError, d.activatedAt = coalesce(d.activatedAt, :activatedAt), d.nextCheckAt = :nextCheckAt,
                d.lastCheckedAt = :now, d.checkCount = d.checkCount + 1, d.updatedAt = :now
            where d.id = :id and d.status = :expectedStatus""")
    int recordCheck(@Param("id") long id, @Param("expectedStatus") CustomDomainStatus expectedStatus, @Param("newStatus") CustomDomainStatus newStatus,
                    @Param("cloudflareStatus") String cloudflareStatus, @Param("sslStatus") String sslStatus, @Param("problem") DomainProblem problem,
                    @Param("lastError") String lastError, @Param("activatedAt") LocalDateTime activatedAt,
                    @Param("nextCheckAt") LocalDateTime nextCheckAt, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.DISABLED, d.disabledReason = :reason, d.disabledNote = :note,
                d.disabledAt = :now, d.nextCheckAt = null, d.updatedAt = :now
            where d.id = :id and d.status = :expectedStatus
              and d.status in (cc.hrva.urlshortener.model.enums.CustomDomainStatus.ACTIVE,
                               cc.hrva.urlshortener.model.enums.CustomDomainStatus.AWAITING_DNS,
                               cc.hrva.urlshortener.model.enums.CustomDomainStatus.APPROVED_PROVISIONING,
                               cc.hrva.urlshortener.model.enums.CustomDomainStatus.ERROR)""")
    int markDisabled(@Param("id") long id, @Param("expectedStatus") CustomDomainStatus expectedStatus, @Param("reason") DomainDisableReason reason,
                     @Param("note") String note, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.AWAITING_DNS, d.disabledReason = null, d.disabledNote = null,
                d.disabledAt = null, d.problem = null, d.nextCheckAt = :now, d.updatedAt = :now
            where d.id = :id and d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.DISABLED""")
    int markEnabled(@Param("id") long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.DELETED, d.deletedAt = :now, d.nextCheckAt = null,
                d.cloudflareDeletePending = case when d.cloudflareId is null then false else true end,
                d.hostnameKey = case when d.cloudflareId is null then null else d.hostnameKey end,
                d.updatedAt = :now
            where d.id = :id and d.status = :expectedStatus
              and d.status <> cc.hrva.urlshortener.model.enums.CustomDomainStatus.DELETED""")
    int markDeleted(@Param("id") long id, @Param("expectedStatus") CustomDomainStatus expectedStatus, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomain d
            set d.cloudflareDeletePending = false, d.hostnameKey = null, d.updatedAt = :now
            where d.id = :id and d.status = cc.hrva.urlshortener.model.enums.CustomDomainStatus.DELETED and d.cloudflareDeletePending = true""")
    int clearDeletePending(@Param("id") long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from CustomDomain d where d.id = :id and d.owner is null and d.cloudflareDeletePending = false")
    int deleteOrphan(@Param("id") long id);

}
