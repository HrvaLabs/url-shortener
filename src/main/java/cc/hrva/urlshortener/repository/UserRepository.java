package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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

    @Query("select u.webauthnUserHandle from User u where u.id = :id")
    Optional<String> findWebauthnUserHandleById(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update User u set u.webauthnUserHandle = :handle where u.id = :id and u.webauthnUserHandle is null")
    int setWebauthnUserHandleIfMissing(@Param("id") Long id, @Param("handle") String handle);
    List<User> findByLastLoginIsLessThanEqualAndActiveTrue(LocalDateTime limitDate);
    long countByCreateDateAfter(LocalDateTime dateTime);

}
