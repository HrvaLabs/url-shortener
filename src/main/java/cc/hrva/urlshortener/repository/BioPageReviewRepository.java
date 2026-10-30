package cc.hrva.urlshortener.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BioPageReviewRepository extends JpaRepository<BioPageReview, Long>, JpaSpecificationExecutor<BioPageReview> {

    Optional<BioPageReview> findFirstByPageAndResolvedAtIsNullOrderByFlaggedAtDesc(BioPage page);
    Optional<BioPageReview> findFirstByPageOrderByFlaggedAtDesc(BioPage page);
    List<BioPageReview> findByPageAndResolvedAtIsNull(BioPage page);
    long countByResolvedAtIsNull();

    @Override
    @EntityGraph(attributePaths = {"page", "page.owner"}, type = EntityGraph.EntityGraphType.LOAD)
    Page<BioPageReview> findAll(Specification<BioPageReview> spec, Pageable pageable);

    @Query("select distinct r.page.id from BioPageReview r where r.resolvedAt is null and r.page.id in :pageIds")
    List<Long> findOpenPageIds(@Param("pageIds") Collection<Long> pageIds);

}
