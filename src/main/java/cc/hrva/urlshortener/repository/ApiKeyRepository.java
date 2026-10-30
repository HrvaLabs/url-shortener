package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long>, JpaSpecificationExecutor<ApiKey> {

    @Override
    @EntityGraph(attributePaths = "owner", type = EntityGraph.EntityGraphType.LOAD)
    Page<ApiKey> findAll(Specification<ApiKey> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "owner.authorities"}, type = EntityGraph.EntityGraphType.LOAD)
    Optional<ApiKey> findApiKeyByKey(String key);
    List<ApiKey> findByOwner(User owner);
    List<ApiKey> findByOwner(User owner, Sort sort);
    List<ApiKey> findByOwnerAndActive(User owner, boolean active, Sort sort);
    Page<ApiKey> findByOwnerAndActiveFalse(User owner, Pageable pageable);
    List<ApiKey> findByOwnerAndActiveTrue(User owner);
    List<ApiKey> findByExpirationDateIsLessThanEqualAndActiveTrue(LocalDateTime createDate);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ApiKey k
            set k.active = case
                    when (k.apiCallsLimit is not null and k.apiCallsUsed + 1 >= k.apiCallsLimit)
                            or (k.expirationDate is not null and k.expirationDate < :now) then false
                    else k.active
                end,
                k.apiCallsUsed = k.apiCallsUsed + 1
            where k.id = :id and k.active = true""")
    int recordUse(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("update ApiKey k set k.active = false where k.owner.id = :ownerId and k.active = true")
    int deactivateActiveByOwnerId(@Param("ownerId") Long ownerId);

    @Modifying
    @Query("delete from ApiKey k where k.id = :id and k.active = false")
    int deleteInactiveById(@Param("id") Long id);

    @Modifying
    @Query("delete from ApiKey k where k.owner.id = :ownerId and k.active = false")
    int deleteInactiveByOwnerId(@Param("ownerId") Long ownerId);

}
