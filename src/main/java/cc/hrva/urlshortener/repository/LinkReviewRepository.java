package cc.hrva.urlshortener.repository;

import java.util.Optional;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LinkReviewRepository extends JpaRepository<LinkReview, Long>, JpaSpecificationExecutor<LinkReview> {

    Optional<LinkReview> findByUrlAndResolvedAtIsNull(Url url);
    Optional<LinkReview> findFirstByUrlOrderByFlaggedAtDesc(Url url);
    Page<LinkReview> findByResolvedAtIsNull(Pageable pageable);
    long countByResolvedAtIsNull();

}
