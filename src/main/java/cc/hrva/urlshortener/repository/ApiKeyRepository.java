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
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long>, JpaSpecificationExecutor<ApiKey> {

    @Override
    @EntityGraph(attributePaths = "owner", type = EntityGraph.EntityGraphType.LOAD)
    Page<ApiKey> findAll(Specification<ApiKey> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "owner.authorities"}, type = EntityGraph.EntityGraphType.LOAD)
    Optional<ApiKey> findApiKeyByKeyHash(String keyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from ApiKey k where k.id = :id")
    Optional<ApiKey> findForUpdateById(@Param("id") Long id);
    @Query("select k.id from ApiKey k where k.keyHash = :keyHash")
    Optional<Long> findIdByKeyHash(@Param("keyHash") String keyHash);
    List<ApiKey> findByOwner(User owner);
    List<ApiKey> findByOwner(User owner, Sort sort);
    List<ApiKey> findByOwnerAndActive(User owner, boolean active, Sort sort);
    Page<ApiKey> findByOwnerAndActiveFalse(User owner, Pageable pageable);
    List<ApiKey> findByOwnerAndActiveTrue(User owner);
    List<ApiKey> findByExpirationDateIsLessThanEqualAndActiveTrue(LocalDateTime createDate);

    @Transactional
    @Modifying
    @Query("""
            update ApiKey k
            set k.apiCallsUsed = coalesce(k.apiCallsUsed, 0) + 1,
                k.lastUsedAt = :now
            where k.id = :id
                and k.active = true
                and (k.expirationDate is null or k.expirationDate >= :now)
                and exists (select u.id from User u where u.id = k.owner.id and (u.active is null or u.active = true))""")
    int recordRequest(@Param("id") Long id, @Param("now") LocalDateTime now);

    boolean existsByIdAndActiveTrue(Long id);

    @Query("select max(k.lastUsedAt) from ApiKey k where k.owner.id = :ownerId")
    Optional<LocalDateTime> findLastUseByOwnerId(@Param("ownerId") Long ownerId);

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
