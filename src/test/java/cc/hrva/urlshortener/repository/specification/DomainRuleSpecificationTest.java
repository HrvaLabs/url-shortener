package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = DomainRuleSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:domain-rule-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class DomainRuleSpecificationTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Autowired
    private DomainRuleRepository domainRuleRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        persistRule("njuskaio.cam", DomainRuleType.BLOCK, "Phishing campaign, September 2026", 1);
        persistRule("jofogas.cards", DomainRuleType.BLOCK, "phishing campaign, September 2026", 2);
        persistRule("gableci.hrva.cc", DomainRuleType.ALLOW, null, 3);
        persistRule("omaehhm.com", DomainRuleType.BLOCK, "Bulk block from All URLs", 4);
        persistRule("padlet.com", DomainRuleType.ALLOW, "Shared site, keep", 5);
        entityManager.flush();
        entityManager.clear();
    }

    private void persistRule(final String domain, final DomainRuleType type, final String reason, final int day) {
        final var rule = DomainRule.builder().domain(domain).type(type).reason(reason).createdBy("admin@hrva.cc").build();
        entityManager.persist(rule);
        entityManager.flush();
        rule.setCreatedAt(LocalDateTime.of(2026, 10, day, 9, 0));
    }

    private List<String> domains(final Specification<DomainRule> spec, final Sort sort) {
        return domainRuleRepository.findAll(spec, PageRequest.of(0, 20, sort)).stream().map(DomainRule::getDomain).toList();
    }

    @Test
    void matchesAPartOfTheDomainInAnyCase() {
        assertThat(domains(DomainRuleSpecification.searchMatches("FOGAS"), NEWEST_FIRST)).containsExactly("jofogas.cards");
        assertThat(domains(DomainRuleSpecification.searchMatches(".c"), NEWEST_FIRST))
                .containsExactly("padlet.com", "omaehhm.com", "gableci.hrva.cc", "jofogas.cards", "njuskaio.cam");
    }

    @Test
    void matchesAPartOfTheReasonInAnyCase() {
        assertThat(domains(DomainRuleSpecification.searchMatches("PHISHING camp"), NEWEST_FIRST)).containsExactly("jofogas.cards", "njuskaio.cam");
        assertThat(domains(DomainRuleSpecification.searchMatches("all urls"), NEWEST_FIRST)).containsExactly("omaehhm.com");
    }

    @Test
    void aRuleWithoutAReasonIsFoundByItsDomain() {
        assertThat(domains(DomainRuleSpecification.searchMatches("gableci"), NEWEST_FIRST)).containsExactly("gableci.hrva.cc");
    }

    @Test
    void aSearchThatMatchesNothingFindsNothing() {
        assertThat(domains(DomainRuleSpecification.searchMatches("nobody"), NEWEST_FIRST)).isEmpty();
    }

    @Test
    void anUnderscoreOrAPercentSignIsNotAWildcard() {
        assertThat(domains(DomainRuleSpecification.searchMatches("_"), NEWEST_FIRST)).isEmpty();
        assertThat(domains(DomainRuleSpecification.searchMatches("%"), NEWEST_FIRST)).isEmpty();

        persistRule("a_b.test", DomainRuleType.BLOCK, "100% spam", 6);
        persistRule("axb.test", DomainRuleType.BLOCK, null, 7);
        entityManager.flush();

        assertThat(domains(DomainRuleSpecification.searchMatches("a_b"), NEWEST_FIRST)).containsExactly("a_b.test");
        assertThat(domains(DomainRuleSpecification.searchMatches("%"), NEWEST_FIRST)).containsExactly("a_b.test");
    }

    @Test
    void hasTypeKeepsOnlyRulesOfThatType() {
        assertThat(domains(DomainRuleSpecification.hasType(DomainRuleType.ALLOW), NEWEST_FIRST)).containsExactly("padlet.com", "gableci.hrva.cc");
        assertThat(domains(DomainRuleSpecification.hasType(DomainRuleType.BLOCK).and(DomainRuleSpecification.searchMatches("phishing")), NEWEST_FIRST))
                .containsExactly("jofogas.cards", "njuskaio.cam");
    }

    @Test
    void rulesSortByDomainEitherWay() {
        final var blocks = DomainRuleSpecification.hasType(DomainRuleType.BLOCK);

        assertThat(domains(blocks, Sort.by("domain"))).containsExactly("jofogas.cards", "njuskaio.cam", "omaehhm.com");
        assertThat(domains(blocks, Sort.by(Sort.Order.desc("domain")))).containsExactly("omaehhm.com", "njuskaio.cam", "jofogas.cards");
    }

    @Test
    void rulesSortByTypeThenNewestFirst() {
        final var sort = Sort.by(Sort.Order.asc("type")).and(NEWEST_FIRST);

        assertThat(domains(Specification.unrestricted(), sort))
                .containsExactly("padlet.com", "gableci.hrva.cc", "omaehhm.com", "jofogas.cards", "njuskaio.cam");
    }

    @Test
    void aPageHoldsItsShareOfTheRulesAndTheTotal() {
        final var first = domainRuleRepository.findAll(Specification.unrestricted(), PageRequest.of(0, 2, NEWEST_FIRST));
        final var last = domainRuleRepository.findAll(Specification.unrestricted(), PageRequest.of(2, 2, NEWEST_FIRST));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getContent()).extracting(DomainRule::getDomain).containsExactly("padlet.com", "omaehhm.com");
        assertThat(last.getContent()).extracting(DomainRule::getDomain).containsExactly("njuskaio.cam");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = DomainRuleRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DomainRuleRepository.class))
    static class TestApplication {
    }

}
