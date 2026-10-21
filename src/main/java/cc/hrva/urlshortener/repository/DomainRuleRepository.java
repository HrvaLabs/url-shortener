package cc.hrva.urlshortener.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainRuleRepository extends JpaRepository<DomainRule, Long> {

    List<DomainRule> findByDomainIn(Collection<String> domains);
    Optional<DomainRule> findByDomain(String domain);
    List<DomainRule> findByType(DomainRuleType type);

}
