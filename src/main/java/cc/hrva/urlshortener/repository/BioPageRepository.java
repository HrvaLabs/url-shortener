package cc.hrva.urlshortener.repository;

import java.util.Optional;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BioPageRepository extends JpaRepository<BioPage, Long>, JpaSpecificationExecutor<BioPage> {

    @Override
    @EntityGraph(attributePaths = "owner")
    Page<BioPage> findAll(Specification<BioPage> spec, Pageable pageable);

    Optional<BioPage> findByOwner(User owner);
    Optional<BioPage> findByHandle(String handle);

    @Query("select p from BioPage p join fetch p.owner where p.handle = :handle")
    Optional<BioPage> findByHandleWithOwner(@Param("handle") String handle);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BioPage p where p.id = :id")
    Optional<BioPage> findByIdForUpdate(@Param("id") Long id);

}
