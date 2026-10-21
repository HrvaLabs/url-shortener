package cc.hrva.urlshortener.repository;

import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LinkReviewRepository extends JpaRepository<LinkReview, Long>, JpaSpecificationExecutor<LinkReview> {

    Optional<LinkReview> findFirstByUrlAndResolvedAtIsNullOrderByFlaggedAtDesc(Url url);
    List<LinkReview> findByUrlAndResolvedAtIsNull(Url url);
    Optional<LinkReview> findFirstByUrlOrderByFlaggedAtDesc(Url url);
    long countByResolvedAtIsNull();

}
