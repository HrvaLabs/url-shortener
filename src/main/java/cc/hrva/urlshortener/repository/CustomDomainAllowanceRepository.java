package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.CustomDomainAllowance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomDomainAllowanceRepository extends JpaRepository<CustomDomainAllowance, Long> {
}
