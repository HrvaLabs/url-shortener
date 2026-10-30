package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.UserSignInMethod;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.UserRepository;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest(
        classes = UserSpecificationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:user-specification;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UserSpecificationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        persistUser("karlo@hrva.cc");
        persistUser("a%%b@example.com");
        persistUser("a%b@example.com");
        persistUser("a_b@example.com");
        persistUser("axb@example.com");
        entityManager.flush();
    }

    private void persistUser(final String email) {
        entityManager.persist(User.builder().email(email).password("password123").apiKeySlots(1L).build());
    }

    private List<String> emails(final Specification<User> spec) {
        return userRepository.findAll(spec, Sort.by("email")).stream().map(User::getEmail).toList();
    }

    @Test
    void searchMatchesAnyPartOfTheEmailIgnoringCase() {
        assertThat(emails(UserSpecification.search("KARLO@HRVA"))).containsExactly("karlo@hrva.cc");
        assertThat(emails(UserSpecification.search("example.com"))).containsExactly("a%%b@example.com", "a%b@example.com", "a_b@example.com", "axb@example.com");
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        assertThat(emails(UserSpecification.search("%%"))).containsExactly("a%%b@example.com");
        assertThat(emails(UserSpecification.search("%"))).containsExactly("a%%b@example.com", "a%b@example.com");
        assertThat(emails(UserSpecification.search("a_b"))).containsExactly("a_b@example.com");
        assertThat(emails(UserSpecification.search("_"))).containsExactly("a_b@example.com");
        assertThat(emails(UserSpecification.search("\\%"))).isEmpty();
    }

    @Test
    void theSignInMethodFilterKeepsAccountsThatUsedTheMethodAtLeastOnce() {
        final var karlo = userRepository.findAll(UserSpecification.search("karlo@hrva.cc")).getFirst();
        final var percent = userRepository.findAll(UserSpecification.search("a%b@")).getFirst();
        recordUse(karlo, SignInMethod.PASSWORD);
        recordUse(karlo, SignInMethod.GITHUB);
        recordUse(percent, SignInMethod.GITHUB);
        entityManager.flush();

        assertThat(emails(UserSpecification.hasSignInMethod(SignInMethod.GITHUB))).containsExactly("a%b@example.com", "karlo@hrva.cc");
        assertThat(emails(UserSpecification.hasSignInMethod(SignInMethod.PASSWORD))).containsExactly("karlo@hrva.cc");
        assertThat(emails(UserSpecification.hasSignInMethod(SignInMethod.PASSKEY))).isEmpty();
        assertThat(emails(UserSpecification.search("karlo").and(UserSpecification.hasSignInMethod(SignInMethod.GITHUB))))
                .containsExactly("karlo@hrva.cc");
    }

    private void recordUse(final User user, final SignInMethod method) {
        final var now = LocalDateTime.of(2026, 10, 29, 9, 0);
        entityManager.persist(UserSignInMethod.builder().userId(user.getId()).method(method).firstUsedAt(now).lastUsedAt(now).build());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(
            basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserRepository.class))
    static class TestApplication {
    }

}
