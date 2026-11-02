package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.DataExportDto.DestinationChangeExport;
import cc.hrva.urlshortener.model.LinkDestinationChange;
import cc.hrva.urlshortener.model.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkDestinationChangeRepository extends JpaRepository<LinkDestinationChange, Long>, JpaSpecificationExecutor<LinkDestinationChange> {

    @Query("""
            select new cc.hrva.urlshortener.dto.DataExportDto$DestinationChangeExport(
                u.shortUrl, d.hostname, c.oldLongUrl, c.newLongUrl, c.via, c.forced, c.changedAt)
            from LinkDestinationChange c join Url u on u.id = c.urlId left join u.domain d
            where u.owner = :owner
            order by c.changedAt desc, c.id desc""")
    List<DestinationChangeExport> exportOf(@Param("owner") User owner);

}
