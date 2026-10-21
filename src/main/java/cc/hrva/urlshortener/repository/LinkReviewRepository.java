package cc.hrva.urlshortener.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkReviewRepository extends JpaRepository<LinkReview, Long>, JpaSpecificationExecutor<LinkReview> {

    @Override
    @EntityGraph(attributePaths = {"url", "url.owner"}, type = EntityGraph.EntityGraphType.LOAD)
    Page<LinkReview> findAll(Specification<LinkReview> spec, Pageable pageable);

    Optional<LinkReview> findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(Url url);
    List<LinkReview> findByUrlAndResolvedAtIsNull(Url url);
    Optional<LinkReview> findFirstByUrlOrderByFlaggedAtDesc(Url url);
    long countByResolvedAtIsNull();
    long countByResolvedAtIsNullAndReasonsContaining(String reason);
    long countByUrlOwnerAndResolvedAtIsNull(User owner);
    boolean existsByUrlAndResolvedAtIsNullAndSystemFlaggedTrue(Url url);

    @Query("select distinct r.url.id from LinkReview r where r.resolvedAt is null and r.url.id in :urlIds")
    List<Long> findOpenUrlIds(@Param("urlIds") Collection<Long> urlIds);

}
