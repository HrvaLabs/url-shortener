package cc.hrva.urlshortener.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BioPageReviewRepository extends JpaRepository<BioPageReview, Long> {

    Optional<BioPageReview> findFirstByPageAndResolvedAtIsNullOrderByFlaggedAtDesc(BioPage page);
    Optional<BioPageReview> findFirstByPageOrderByFlaggedAtDesc(BioPage page);
    List<BioPageReview> findByPageAndResolvedAtIsNull(BioPage page);
    long countByResolvedAtIsNull();

    @Query(value = "select r from BioPageReview r join fetch r.page p join fetch p.owner where r.resolvedAt is null order by r.flaggedAt desc, r.id desc",
            countQuery = "select count(r) from BioPageReview r where r.resolvedAt is null")
    Page<BioPageReview> findOpen(Pageable pageable);

    @Query("select distinct r.page.id from BioPageReview r where r.resolvedAt is null and r.page.id in :pageIds")
    List<Long> findOpenPageIds(@Param("pageIds") Collection<Long> pageIds);

}
