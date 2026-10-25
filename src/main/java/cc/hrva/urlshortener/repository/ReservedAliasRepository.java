package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.ReservedAlias;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservedAliasRepository extends JpaRepository<ReservedAlias, Long> {

    boolean existsByAlias(String alias);
    List<ReservedAlias> findAllByOrderByAliasAsc();

}
