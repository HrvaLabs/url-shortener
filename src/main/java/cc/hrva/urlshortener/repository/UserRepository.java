package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    boolean existsByEmail(String email);
    boolean existsByIdAndAuthoritiesName(Long id, String name);
    @EntityGraph(attributePaths = "authorities", type = EntityGraph.EntityGraphType.LOAD)
    Optional<User> findByEmail(String email);
    Optional<User> findByWebauthnUserHandle(String handle);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Query("select u.webauthnUserHandle from User u where u.id = :id")
    Optional<String> findWebauthnUserHandleById(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update User u set u.webauthnUserHandle = :handle where u.id = :id and u.webauthnUserHandle is null")
    int setWebauthnUserHandleIfMissing(@Param("id") Long id, @Param("handle") String handle);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update User u set u.lastLogin = :signedInAt where u.id = :id")
    int recordLastLogin(@Param("id") Long id, @Param("signedInAt") LocalDateTime signedInAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.active = true where u.id = :id and u.active = false")
    int reactivateById(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.active = false where u.id = :id and (u.active is null or u.active = true)")
    int deactivateById(@Param("id") Long id);

    List<User> findByLastLoginIsLessThanEqualAndActiveTrue(LocalDateTime limitDate);
    long countByCreateDateAfter(LocalDateTime dateTime);

}
