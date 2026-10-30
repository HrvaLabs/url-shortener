package cc.hrva.urlshortener.repository;

import java.util.Collection;
import java.util.List;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BioPageButtonRepository extends JpaRepository<BioPageButton, Long> {

    @Query("select b from BioPageButton b join fetch b.url u left join fetch u.domain where b.page = :page order by b.sortOrder")
    List<BioPageButton> findByPageWithUrl(@Param("page") BioPage page);

    @Query("select new cc.hrva.urlshortener.repository.BioButtonCount(b.page.id, count(b), sum(case when b.visible = true then 1 else 0 end))"
            + " from BioPageButton b where b.page.id in :pageIds group by b.page.id")
    List<BioButtonCount> countByPageIds(@Param("pageIds") Collection<Long> pageIds);

}
