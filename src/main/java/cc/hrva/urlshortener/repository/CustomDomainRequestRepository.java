package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.CustomDomainRequest;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
public interface CustomDomainRequestRepository extends JpaRepository<CustomDomainRequest, Long>, JpaSpecificationExecutor<CustomDomainRequest> {

    @Override
    @EntityGraph(attributePaths = "user", type = EntityGraph.EntityGraphType.LOAD)
    Page<CustomDomainRequest> findAll(Specification<CustomDomainRequest> spec, Pageable pageable);

    @Query("select r from CustomDomainRequest r join fetch r.user where r.id = :id")
    Optional<CustomDomainRequest> findWithUser(@Param("id") long id);

    Optional<CustomDomainRequest> findFirstByUser_IdOrderByCreatedAtDescIdDesc(Long userId);

    List<CustomDomainRequest> findByUser_IdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<CustomDomainRequest> findByIdAndUser_Id(Long id, Long userId);

    boolean existsByUser_IdAndStatus(Long userId, DomainRequestStatus status);

    long countByStatus(DomainRequestStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update CustomDomainRequest r
            set r.status = :status, r.reviewedBy = :reviewedBy, r.adminNote = :note, r.reviewedAt = :now
            where r.id = :id and r.status = cc.hrva.urlshortener.model.enums.DomainRequestStatus.WAITING""")
    int markReviewed(@Param("id") long id, @Param("status") DomainRequestStatus status, @Param("reviewedBy") String reviewedBy,
                     @Param("note") String note, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            delete from CustomDomainRequest r
            where r.id = :id and r.user.id = :userId and r.status = cc.hrva.urlshortener.model.enums.DomainRequestStatus.WAITING""")
    int deleteWaiting(@Param("id") long id, @Param("userId") long userId);

}
