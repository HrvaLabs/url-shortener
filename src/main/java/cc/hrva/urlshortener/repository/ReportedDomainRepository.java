package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.ReportedDomain;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportedDomainRepository extends JpaRepository<ReportedDomain, String> {
}
