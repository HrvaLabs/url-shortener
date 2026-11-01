package cc.hrva.urlshortener.repository;

import java.time.Instant;
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
    @Transactional(readOnly = true, timeout = 3)
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

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update User u set u.inactivityWarnedAt = :now where u.id = :id and (u.inactivityWarnedAt is null or u.inactivityWarnedAt < :activity)")
    int claimInactivityWarning(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("activity") LocalDateTime activity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update User u set u.password = :password, u.emailVerified = true where u.id = :id and (u.emailVerified is null or u.emailVerified = false)")
    int confirmEmailAndReplacePassword(@Param("id") Long id, @Param("password") String password);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update User u set u.password = :password where u.id = :id")
    int replacePassword(@Param("id") Long id, @Param("password") String password);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.active = true where u.id = :id and u.active = false")
    int reactivateById(@Param("id") Long id);

    default int deactivateById(final Long id) {
        return deactivateAndEndSessionsById(id, User.sessionCutOffOf(Instant.now()));
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.active = false, u.tokensValidAfter = :endedAt where u.id = :id and (u.active is null or u.active = true)")
    int deactivateAndEndSessionsById(@Param("id") Long id, @Param("endedAt") LocalDateTime endedAt);

    List<User> findByLastLoginIsLessThanEqualAndActiveTrue(LocalDateTime limitDate);
    long countByCreateDateAfter(LocalDateTime dateTime);

}
