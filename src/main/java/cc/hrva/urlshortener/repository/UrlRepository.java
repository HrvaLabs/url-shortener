package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.dto.LinkDestination;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
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
public interface UrlRepository extends JpaRepository<Url, Long>, JpaSpecificationExecutor<Url> {

    default Optional<Url> findServed(final Long domainId, final String alias) {
        return domainId == null
                ? findFirstByDomainIsNullAndShortUrlOrderByActiveDescIdDesc(alias)
                : findFirstByDomain_IdAndShortUrlOrderByActiveDescIdDesc(domainId, alias);
    }

    default Optional<Url> findActive(final Long domainId, final String alias) {
        return domainId == null ? findByDomainIsNullAndShortUrlAndActiveTrue(alias) : findByDomain_IdAndShortUrlAndActiveTrue(domainId, alias);
    }

    default boolean aliasTaken(final Long domainId, final String alias) {
        return domainId == null ? existsByDomainIsNullAndShortUrl(alias) : existsByDomain_IdAndShortUrl(domainId, alias);
    }

    default boolean aliasActive(final Long domainId, final String alias) {
        return domainId == null ? existsByDomainIsNullAndShortUrlAndActiveTrue(alias) : existsByDomain_IdAndShortUrlAndActiveTrue(domainId, alias);
    }

    @Override
    @EntityGraph(attributePaths = {"owner", "domain"}, type = EntityGraph.EntityGraphType.LOAD)
    Page<Url> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"owner", "domain"}, type = EntityGraph.EntityGraphType.LOAD)
    Page<Url> findAll(Specification<Url> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "domain"}, type = EntityGraph.EntityGraphType.LOAD)
    List<Url> findTop10ByOrderByCreateDateDescIdDesc();

    Page<Url> findAllByOwner(User owner, Pageable pageable);
    boolean existsUrlByLongUrlAndActiveTrue(String longUrl);
    Optional<Url> findByLongUrlAndActiveTrue(String longUrl);
    Optional<Url> findFirstByDomainIsNullAndShortUrlOrderByActiveDescIdDesc(String alias);
    Optional<Url> findFirstByDomain_IdAndShortUrlOrderByActiveDescIdDesc(Long domainId, String alias);
    Optional<Url> findByDomainIsNullAndShortUrlAndActiveTrue(String alias);
    Optional<Url> findByDomain_IdAndShortUrlAndActiveTrue(Long domainId, String alias);
    boolean existsByDomainIsNullAndShortUrl(String alias);
    boolean existsByDomain_IdAndShortUrl(Long domainId, String alias);
    boolean existsByDomainIsNullAndShortUrlAndActiveTrue(String alias);
    boolean existsByDomain_IdAndShortUrlAndActiveTrue(Long domainId, String alias);
    boolean existsUrlByLongUrlAndActiveTrueAndOwnerIsNull(String longUrl);
    @EntityGraph(attributePaths = "owner", type = EntityGraph.EntityGraphType.LOAD)
    List<Url> findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(LocalDateTime start, LocalDateTime end);
    long countByActiveTrue();
    List<Url> findTop10ByCreateDateAfterAndOwnerCreateDateAfterOrderByCreateDateDesc(LocalDateTime createdAfter, LocalDateTime ownerCreatedAfter);
    List<Url> findTop20ByBlockedAtAfterAndBlockReasonNotInOrderByBlockedAtDesc(LocalDateTime blockedAfter, Collection<BlockReason> excludedReasons);
    long countByOwnerAndBlockReasonIsNotNull(User owner);
    long countByBlockReasonIsNotNullAndBlockedAtAfter(LocalDateTime blockedAfter);
    List<Url> findByOwner(User owner);
    void deleteByOwner(User owner);

    @Query("select u.id from Url u where u.active = true order by u.id")
    List<Long> findActiveIds();

    @Query("select u.id from Url u where u.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    @Query("""
            select u from Url u
            where u.owner.id = :ownerId and u.active = true
              and u.destinationDeadSince >= :from and u.destinationDeadSince < :to
            order by u.destinationDeadSince, u.id""")
    List<Url> findOwnerLinksDeadBetween(@Param("ownerId") long ownerId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                        Limit limit);

    @Query("""
            select u from Url u
            where u.owner.id = :ownerId and u.active = true
              and ((u.expirationDate > :now and u.expirationDate <= :until)
                or (u.visitLimit is not null
                    and u.visitLimit - coalesce(u.visits, 0) >= 1
                    and (u.visitLimit - coalesce(u.visits, 0) - 1) * 10 < u.visitLimit))
            order by u.expirationDate asc nulls last, u.id""")
    List<Url> findOwnerLinksEndingSoon(@Param("ownerId") long ownerId, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until,
                                       Limit limit);

    @Query("""
            select count(u) from Url u
            where u.owner.id = :ownerId and u.active = true
              and u.destinationDeadSince >= :from and u.destinationDeadSince < :to""")
    long countOwnerLinksDeadBetween(@Param("ownerId") long ownerId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            select count(u) from Url u
            where u.owner.id = :ownerId and u.active = true
              and ((u.expirationDate > :now and u.expirationDate <= :until)
                or (u.visitLimit is not null
                    and u.visitLimit - coalesce(u.visits, 0) >= 1
                    and (u.visitLimit - coalesce(u.visits, 0) - 1) * 10 < u.visitLimit))""")
    long countOwnerLinksEndingSoon(@Param("ownerId") long ownerId, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Query("select u.id from Url u where u.id in :ids and u.active = true and u.destinationDeadSince is not null")
    List<Long> findActiveStillDeadIds(@Param("ids") Collection<Long> ids);

    @Query("select u from Url u left join fetch u.owner where lower(u.shortUrl) in :aliases")
    List<Url> findByLowerShortUrlIn(@Param("aliases") Collection<String> aliases);

    @Query("select u.id from Url u where u.expirationDate <= :now and u.active = true order by u.id")
    List<Long> findExpiredActiveIds(@Param("now") LocalDateTime now);

    @Query("select new cc.hrva.urlshortener.dto.LinkDestination(u.id, u.longUrl) from Url u where u.active = true and u.id > :afterId order by u.id")
    List<LinkDestination> findActiveDestinationsAfter(@Param("afterId") long afterId, Pageable pageable);

    @Query("select new cc.hrva.urlshortener.dto.LinkDestination(u.id, u.longUrl) from Url u where u.id in :ids")
    List<LinkDestination> findDestinationsByIdIn(@Param("ids") Collection<Long> ids);

    @Query("select new cc.hrva.urlshortener.dto.LinkDestination(u.id, u.longUrl) from Url u "
            + "where u.active = false and u.blockReason in :reasons and u.id > :afterId order by u.id")
    List<LinkDestination> findBlockedDestinationsAfter(
            @Param("reasons") Collection<BlockReason> reasons, @Param("afterId") long afterId, Pageable pageable);

    @Query("select u.longUrl from Url u where (lower(u.longUrl) like concat('%', :domain, '%') "
            + "or lower(u.longUrl) like concat('%', :unicodeDomain, '%')) "
            + "and (u.blockReason is not null "
            + "or exists (select r.id from LinkReview r where r.url = u and r.resolvedAt is null and r.systemFlagged = true))")
    List<String> findFlaggedOrBlockedDestinationsMentioning(@Param("domain") String domain, @Param("unicodeDomain") String unicodeDomain);

    @Query("select count(u) from Url u where u.active = true "
            + "and not exists (select r.id from LinkReview r where r.url = u and r.resolvedAt is null and r.systemFlagged = true) "
            + "and (lower(u.longUrl) like concat('%/', :domain, '%') or lower(u.longUrl) like concat('%.', :domain, '%') "
            + "or lower(u.longUrl) like concat('%@', :domain, '%') or lower(u.longUrl) like concat('%/', :unicodeDomain, '%') "
            + "or lower(u.longUrl) like concat('%.', :unicodeDomain, '%') or lower(u.longUrl) like concat('%@', :unicodeDomain, '%'))")
    long countActiveUnflaggedLinksMentioning(@Param("domain") String domain, @Param("unicodeDomain") String unicodeDomain);

    @Query("select u.id from Url u where u.owner = :owner and u.active = true order by u.id")
    List<Long> findActiveIdsByOwner(@Param("owner") User owner);

    @Query("select u.id from Url u where u.owner = :owner and u.blockReason = :reason order by u.id")
    List<Long> findIdsByOwnerAndBlockReason(@Param("owner") User owner, @Param("reason") BlockReason reason);

    @Query("select distinct t from Url u join u.tags t where u.owner = :owner order by t")
    List<String> findDistinctTagsByOwner(@Param("owner") User owner);

    @Query("select count(u) from Url u where u.owner = :owner and exists (select r.id from LinkReview r where r.url = u "
            + "and r.systemFlagged = true and (r.resolution is null or r.resolution not in (cc.hrva.urlshortener.model.enums.ReviewResolution.OK, cc.hrva.urlshortener.model.enums.ReviewResolution.DOMAIN_TRUSTED)))")
    long countFlaggedByOwner(@Param("owner") User owner);

    @Query("select o.id from Url u join u.owner o where coalesce(o.active, true) = true and u.blockReason is not null "
            + "and not exists (select a.id from User a join a.authorities role where a = o and role.name = 'ROLE_ADMIN') "
            + "group by o.id having count(u) >= :links")
    List<Long> findOwnerIdsWithBlockedLinks(@Param("links") long links);

    @Query("select o.id from Url u join u.owner o where coalesce(o.active, true) = true and o.createDate > :signedUpAfter "
            + "and not exists (select a.id from User a join a.authorities role where a = o and role.name = 'ROLE_ADMIN') "
            + "and (u.blockReason is not null or exists (select r.id from LinkReview r where r.url = u and r.systemFlagged = true "
            + "and (r.resolution is null or r.resolution not in (cc.hrva.urlshortener.model.enums.ReviewResolution.OK, cc.hrva.urlshortener.model.enums.ReviewResolution.DOMAIN_TRUSTED)))) "
            + "group by o.id having count(u) >= :links")
    List<Long> findOwnerIdsSignedUpAfterWithFlaggedOrBlockedLinks(@Param("signedUpAfter") LocalDateTime signedUpAfter, @Param("links") long links);

    @Query("select o.id from Url u join u.owner o where coalesce(o.active, true) = true and o.createDate > :signedUpAfter "
            + "and not exists (select a.id from User a join a.authorities role where a = o and role.name = 'ROLE_ADMIN') "
            + "and (u.createDate - o.createDate) by second < :windowSeconds "
            + "group by o.id having count(u) >= :links")
    List<Long> findOwnerIdsWithLinksSoonAfterSignup(@Param("signedUpAfter") LocalDateTime signedUpAfter, @Param("windowSeconds") long windowSeconds,
            @Param("links") long links);

    @Modifying
    @Transactional
    @Query("update Url u set u.lastSafeBrowsingCheck = :checkedAt where u.id in :ids")
    int markSafeBrowsingChecked(@Param("ids") Collection<Long> ids, @Param("checkedAt") LocalDateTime checkedAt);

    @Query("select u.longUrl from Url u where u.id = :id")
    Optional<String> findLongUrlById(@Param("id") long id);

    @Query("select u.id from Url u where u.active = true and (u.chainCheckedAt is null or u.chainCheckedAt < :checkedBefore) "
            + "order by u.chainCheckedAt asc nulls first, u.id")
    List<Long> findIdsDueForRedirectCheck(@Param("checkedBefore") LocalDateTime checkedBefore, Pageable pageable);

    @Modifying
    @Transactional
    @Query("update Url u set u.finalUrl = :finalUrl, u.chainCheckedAt = :checkedAt where u.id = :id and u.longUrl = :longUrl")
    int recordRedirectChain(@Param("id") long id, @Param("longUrl") String longUrl, @Param("finalUrl") String finalUrl,
            @Param("checkedAt") LocalDateTime checkedAt);

    @Modifying
    @Transactional
    @Query("update Url u set u.chainCheckedAt = :checkedAt where u.id = :id and u.longUrl = :longUrl")
    int markRedirectChainChecked(@Param("id") long id, @Param("longUrl") String longUrl, @Param("checkedAt") LocalDateTime checkedAt);

    @Modifying
    @Transactional
    @Query("update Url u set u.visits = u.visits + 1, u.lastAccessed = :visitedAt where u.id = :id and u.active = true")
    int recordVisit(@Param("id") long id, @Param("visitedAt") LocalDateTime visitedAt);

    @Modifying
    @Transactional
    @Query("update Url u set u.active = false where u.id = :id and u.active = true and u.visitLimit is not null and u.visits >= u.visitLimit")
    int deactivateAtVisitLimit(@Param("id") long id);

    @Modifying
    @Transactional
    @Query("update Url u set u.active = false where u.id in :ids and u.active = true")
    int deactivateIfActive(@Param("ids") Collection<Long> ids);

}
