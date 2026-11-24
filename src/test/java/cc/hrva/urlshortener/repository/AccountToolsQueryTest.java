package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.tools.AccountTools;
import cc.hrva.urlshortener.mcp.tools.McpUserResolver;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Transactional
@SpringBootTest(
        classes = AccountToolsQueryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:account-tools-query;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AccountToolsQueryTest {

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private LinkReviewRepository linkReviewRepository;

    @Autowired
    private EntityManager entityManager;

    private AccountTools accountTools;
    private User owner;

    @BeforeEach
    void setUp() {
        final var admin = User.builder().id(1L).email("admin@hrva.cc").active(true).twoStepEnabled(true).build();
        final var mcpUserResolver = mock(McpUserResolver.class);
        final var userRepository = mock(UserRepository.class);
        owner = user("account@example.com");
        final var other = user("other@example.com");
        when(mcpUserResolver.currentUser()).thenReturn(admin);
        when(userRepository.existsByIdAndAuthoritiesName(1L, "ROLE_ADMIN")).thenReturn(true);
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        final var appProperties = new AppProperties();
        appProperties.getMcp().setIssuer("https://hrva.cc");
        accountTools = new AccountTools(mcpUserResolver, userRepository, urlRepository, linkReviewRepository, mock(CustomDomainRepository.class),
                mock(BioPageRepository.class), mock(ScammerSignalService.class), appProperties, new ShortLinks(appProperties), Clock.systemUTC());

        link("live", owner, null);
        link("off", owner, null);
        link("blocked", owner, BlockReason.ADMIN);
        final var reviewed = link("flagged", owner, null);
        link("50_off", owner, null);
        link("50xoff", owner, null);
        link("foreign", other, null);
        link("foreign-off", other, null);
        entityManager.persist(LinkReview.builder().url(reviewed).reasons("reason").flaggedAt(LocalDateTime.of(2026, 11, 1, 9, 0)).build());
        entityManager.flush();
        entityManager.createQuery("update Url u set u.active = false where u.shortUrl in ('off', 'foreign-off')").executeUpdate();
        entityManager.clear();
    }

    @Test
    void onlyTheAskedAccountsLinksAreListed() {
        final var result = text(accountTools.accountLinks(owner.getId(), null, null, null, "oldest"));

        assertThat(result.lines().toList().getFirst()).startsWith("6 links match for a***@example.com");
        assertThat(result).contains("/live ", "/off ", "/blocked ", "/flagged ", "/50_off ", "/50xoff ")
                .doesNotContain("/foreign");
    }

    @Test
    void stateFiltersReturnExactlyTheMatchingLinksInAnyCaseAndWithSpaces() {
        assertThat(aliases(" Turned_Off ")).containsExactly("off");
        assertThat(aliases("TURNED_OFF")).containsExactly("off");
        assertThat(aliases(" In_Review ")).containsExactly("flagged");
        assertThat(aliases("blocked")).containsExactly("blocked");
        assertThat(aliases("ACTIVE")).containsExactlyInAnyOrder("live", "flagged", "50_off", "50xoff");
    }

    @Test
    void searchMatchesTheUnderscoreLiterally() {
        final var result = text(accountTools.accountLinks(owner.getId(), null, null, "50_", null));

        assertThat(result.lines().toList().getFirst()).isEqualTo("1 link match for a***@example.com #%d, page 1 of 1, newest first, search <50_>."
                .formatted(owner.getId()));
        assertThat(result).contains("/50_off ").doesNotContain("/50xoff ", "/live ");
    }

    private java.util.List<String> aliases(final String state) {
        return text(accountTools.accountLinks(owner.getId(), null, state, null, null)).lines()
                .skip(1)
                .map(line -> line.split(" \\| ")[1].split(" -> ")[0].replace("https://hrva.cc/", ""))
                .toList();
    }

    private User user(final String email) {
        final var user = User.builder().email(email).password("password123").apiKeySlots(1L).build();
        entityManager.persist(user);
        return user;
    }

    private Url link(final String shortUrl, final User linkOwner, final BlockReason blockReason) {
        final var url = Url.builder().shortUrl(shortUrl).longUrl("https://" + shortUrl + ".example/").owner(linkOwner).blockReason(blockReason).build();
        entityManager.persist(url);
        return url;
    }

    private static String text(final CallToolResult result) {
        assertThat(result.isError()).isFalse();
        return ((TextContent) result.content().getFirst()).text();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, LinkReviewRepository.class}))
    static class TestApplication {
    }

}
