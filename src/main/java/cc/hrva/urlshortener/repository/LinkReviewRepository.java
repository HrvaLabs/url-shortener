package cc.hrva.urlshortener.repository;

import java.util.Optional;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkReviewRepository extends JpaRepository<LinkReview, Long> {

    Optional<LinkReview> findByUrlAndResolvedAtIsNull(Url url);
    Optional<LinkReview> findFirstByUrlOrderByFlaggedAtDesc(Url url);
    Page<LinkReview> findByResolvedAtIsNull(Pageable pageable);
    long countByResolvedAtIsNull();

}
