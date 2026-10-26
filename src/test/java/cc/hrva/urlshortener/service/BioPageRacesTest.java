package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.dto.bio.BioPageButtonDto;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.impl.DefaultBioPageService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = BioPageRacesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:bio-page-races;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageRacesTest {

    private static final String PAGE_CHANGED = "This page changed. Reload and try again.";
    private static final String NO_PAGE = "You don't have a bio page yet.";

    @Autowired
    private BioPageService bioPageService;

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    @MockitoSpyBean
    private BioPageResponses bioPageResponses;

    @MockitoSpyBean
    private BioHandles bioHandles;

    private final AtomicBoolean otherRequestRan = new AtomicBoolean();
    private TransactionTemplate otherRequest;
    private User ana;
    private BioPage page;
    private Url instagram;

    @BeforeEach
    void setUp() {
        otherRequest = new TransactionTemplate(transactionManager);
        otherRequest.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).build());
        page = bioPageRepository.saveAndFlush(BioPage.builder().owner(ana).handle("ana").build());
        instagram = urlRepository.saveAndFlush(Url.builder().shortUrl("ana-ig").longUrl("https://instagram.com/ana").owner(ana).build());
        when(userService.getUserFromToken()).thenReturn(ana);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from bio_handle_hold");
        jdbcTemplate.update("delete from bio_page where user_id = ?", ana.getId());
        jdbcTemplate.update("delete from url where id = ?", instagram.getId());
        jdbcTemplate.update("delete from user_account where id = ?", ana.getId());
    }

    @Test
    void aSaveThatLosesTheRaceForANewLinkIs409() {
        final var addInstagram = addingTheLink(instagram);
        doAnswer(invocation -> {
            if (otherRequestRan.compareAndSet(false, true)) {
                otherRequest.executeWithoutResult(status -> bioPageService.update(addInstagram));
            }
            return invocation.callRealMethod();
        }).when(bioPageResponses).statesOf(anyCollection());

        assertThatThrownBy(() -> bioPageService.update(addInstagram))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(PAGE_CHANGED);

        assertThat(otherRequestRan).isTrue();
        assertThat(jdbcTemplate.queryForObject("select count(*) from bio_page_button where page_id = ?", Long.class, page.getId()))
                .isEqualTo(1L);
    }

    @Test
    void aDeleteThatLosesTheRaceForTheHandleHoldIs404() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (otherRequestRan.compareAndSet(false, true)) {
                otherRequest.executeWithoutResult(status -> bioPageService.delete());
            }
            return null;
        }).when(bioHandles).hold(eq("ana"), isNull());

        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);

        assertThat(otherRequestRan).isTrue();
        assertDeletedOnceAndHeld();
    }

    @Test
    void aDeleteThatStartsBeforeTheOtherFinishesIs404() {
        doAnswer(invocation -> {
            if (otherRequestRan.compareAndSet(false, true)) {
                otherRequest.executeWithoutResult(status -> bioPageService.delete());
            }
            return invocation.callRealMethod();
        }).when(bioHandles).hold(eq("ana"), isNull());

        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);

        assertThat(otherRequestRan).isTrue();
        assertDeletedOnceAndHeld();
    }

    private void assertDeletedOnceAndHeld() {
        assertThat(bioPageRepository.findById(page.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForList("select handle from bio_handle_hold where page_id is null", String.class))
                .containsExactly("ana");
    }

    private static BioPageUpdateDto addingTheLink(final Url url) {
        return BioPageUpdateDto.builder()
                .displayName("Ana")
                .bio("")
                .avatarKind(BioAvatarKind.INITIALS)
                .accent(BioAccent.BLUE)
                .theme(BioTheme.AUTO)
                .buttons(List.of(BioPageButtonDto.builder().urlId(url.getId()).title("Instagram").build()))
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BioPage.class)
    @EnableJpaRepositories(
            basePackageClasses = BioPageRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            BioPageRepository.class,
                            BioPageButtonRepository.class,
                            BioHandleHoldRepository.class,
                            UrlRepository.class,
                            UserRepository.class,
                            LinkReviewRepository.class}))
    @Import({ClockConfiguration.class, BioHandles.class, BioPageResponses.class, DefaultBioPageService.class})
    static class TestApplication {
    }

}
