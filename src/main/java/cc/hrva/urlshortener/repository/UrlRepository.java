package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
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

    @Query("select distinct t from Url u join u.tags t where u.owner = :owner order by t")
    List<String> findDistinctTagsByOwner(@Param("owner") User owner);

}
