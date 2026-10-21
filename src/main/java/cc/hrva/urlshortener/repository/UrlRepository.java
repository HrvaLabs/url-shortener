package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.dto.LinkDestination;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long>, JpaSpecificationExecutor<Url> {

    Page<Url> findAllByOwner(User owner, Pageable pageable);
    boolean existsUrlByLongUrlAndActiveTrue(String longUrl);
    Optional<Url> findByLongUrlAndActiveTrue(String longUrl);
    boolean existsUrlByShortUrl(String shortUrl);
    boolean existsUrlByShortUrlAndActiveTrue(String shortUrl);
    Optional<Url> findByShortUrlAndActiveTrue(String shortUrl);
    boolean existsUrlByLongUrlAndActiveTrueAndOwnerIsNull(String longUrl);
    List<Url> findByExpirationDateLessThanEqualAndActiveTrue(LocalDateTime expirationDate);
    List<Url> findByExpirationDateBetweenAndActiveTrueAndOwnerIsNotNull(LocalDateTime start, LocalDateTime end);
    long countByActiveTrue();
    List<Url> findByCreateDateAfter(LocalDateTime createdAfter);
    List<Url> findByOwner(User owner);
    void deleteByOwner(User owner);

    @Query("select u.id from Url u where u.active = true order by u.id")
    List<Long> findActiveIds();

    @Query("select new cc.hrva.urlshortener.dto.LinkDestination(u.id, u.longUrl) from Url u where u.active = true and u.id > :afterId order by u.id")
    List<LinkDestination> findActiveDestinationsAfter(@Param("afterId") long afterId, Pageable pageable);

    @Query("select new cc.hrva.urlshortener.dto.LinkDestination(u.id, u.longUrl) from Url u "
            + "where u.active = false and u.blockReason in :reasons and u.id > :afterId order by u.id")
    List<LinkDestination> findBlockedDestinationsAfter(
            @Param("reasons") Collection<BlockReason> reasons, @Param("afterId") long afterId, Pageable pageable);

    @Query("select u.id from Url u where u.owner = :owner and u.active = true order by u.id")
    List<Long> findActiveIdsByOwner(@Param("owner") User owner);

    @Query("select u.id from Url u where u.owner = :owner and u.blockReason = :reason order by u.id")
    List<Long> findIdsByOwnerAndBlockReason(@Param("owner") User owner, @Param("reason") BlockReason reason);

    @Query("select distinct t from Url u join u.tags t where u.owner = :owner order by t")
    List<String> findDistinctTagsByOwner(@Param("owner") User owner);

}
