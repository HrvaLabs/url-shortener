package cc.hrva.urlshortener.repository;

import java.util.List;
import cc.hrva.urlshortener.model.BlocklistSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlocklistSourceRepository extends JpaRepository<BlocklistSource, Long> {

    List<BlocklistSource> findByEnabledTrue();

}
