package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.LinkDestinationChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LinkDestinationChangeRepository extends JpaRepository<LinkDestinationChange, Long>, JpaSpecificationExecutor<LinkDestinationChange> {

}
