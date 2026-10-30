package cc.hrva.urlshortener.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DomainRuleRepository extends JpaRepository<DomainRule, Long>, JpaSpecificationExecutor<DomainRule> {

    List<DomainRule> findByDomainIn(Collection<String> domains);
    Optional<DomainRule> findByDomain(String domain);
    List<DomainRule> findByType(DomainRuleType type);
    long countByType(DomainRuleType type);

}
