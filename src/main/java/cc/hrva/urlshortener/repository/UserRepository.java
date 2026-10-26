package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    boolean existsByEmail(String email);
    boolean existsByIdAndAuthoritiesName(Long id, String name);
    @EntityGraph(attributePaths = "authorities", type = EntityGraph.EntityGraphType.LOAD)
    Optional<User> findByEmail(String email);
    List<User> findByLastLoginIsLessThanEqualAndActiveTrue(LocalDateTime limitDate);
    long countByCreateDateAfter(LocalDateTime dateTime);

}
