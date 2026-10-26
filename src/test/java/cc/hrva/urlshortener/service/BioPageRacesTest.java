package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.dto.bio.BioPageButtonDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
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
import cc.hrva.urlshortener.service.bio.BioPageLock;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.impl.DefaultBioPageService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
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
                "spring.datasource.url=jdbc:h2:mem:bio-page-races;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=200",
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
    private final AtomicReference<Throwable> otherRequestFailure = new AtomicReference<>();
    private TransactionTemplate otherRequest;
    private User ana;
    private BioPage page;
    private Url instagram;
    private Url blog;

    @BeforeEach
    void setUp() {
        otherRequest = new TransactionTemplate(transactionManager);
        otherRequest.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).build());
        page = bioPageRepository.saveAndFlush(BioPage.builder().owner(ana).handle("ana").build());
        instagram = urlRepository.saveAndFlush(Url.builder().shortUrl("ana-ig").longUrl("https://instagram.com/ana").owner(ana).build());
        blog = urlRepository.saveAndFlush(Url.builder().shortUrl("ana-blog").longUrl("https://blog.example.com/ana").owner(ana).build());
        when(userService.getUserFromToken()).thenReturn(ana);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from bio_handle_hold");
        jdbcTemplate.update("delete from bio_page where user_id = ?", ana.getId());
        jdbcTemplate.update("delete from url where owner_id = ?", ana.getId());
        jdbcTemplate.update("delete from user_account where id = ?", ana.getId());
    }

    @Test
    void twoSavesAddingTheSameLinkTakeTurns() {
        final var addInstagram = saving(button(instagram, "Instagram"));
        whileTheButtonsAreRead(() -> bioPageService.update(addInstagram));

        final var first = bioPageService.update(addInstagram);

        assertTheOtherRequestWaitedForTheLock();
        assertThat(first.buttons()).extracting(BioPageResponse.Button::shortUrl).containsExactly("ana-ig");
        final var second = bioPageService.update(addInstagram);
        assertThat(second.buttons()).extracting(BioPageResponse.Button::id).containsExactly(first.buttons().getFirst().id());
        assertThat(buttonsOnThePage()).isEqualTo(1L);
    }

    @Test
    void twoSavesAddingDifferentLinksInOppositeOrdersTakeTurns() {
        final var blogLast = saving(button(instagram, "Instagram"), button(blog, "Blog"));
        final var blogFirst = saving(button(blog, "Blog"), button(instagram, "Instagram"));
        whileTheButtonsAreRead(() -> bioPageService.update(blogFirst));

        final var first = bioPageService.update(blogLast);

        assertTheOtherRequestWaitedForTheLock();
        assertThat(first.buttons()).extracting(BioPageResponse.Button::shortUrl).containsExactly("ana-ig", "ana-blog");
        assertThat(bioPageService.update(blogFirst).buttons()).extracting(BioPageResponse.Button::shortUrl).containsExactly("ana-blog", "ana-ig");
        assertThat(buttonsOnThePage()).isEqualTo(2L);
    }

    @Test
    void aSaveThatRemovesAButtonWaitsForASaveThatEditsIt() {
        bioPageService.update(saving(button(instagram, "Instagram")));
        whileTheButtonsAreRead(() -> bioPageService.update(saving()));

        final var edited = bioPageService.update(saving(button(instagram, "Insta")));

        assertTheOtherRequestWaitedForTheLock();
        assertThat(edited.buttons()).extracting(BioPageResponse.Button::title).containsExactly("Insta");
        assertThat(bioPageService.update(saving()).buttons()).isEmpty();
        assertThat(buttonsOnThePage()).isZero();
    }

    @Test
    void aDeleteWaitsForASaveInProgress() {
        whileTheButtonsAreRead(() -> bioPageService.delete());

        final var saved = bioPageService.update(saving(button(instagram, "Instagram")));

        assertTheOtherRequestWaitedForTheLock();
        assertThat(saved.buttons()).extracting(BioPageResponse.Button::shortUrl).containsExactly("ana-ig");
        bioPageService.delete();
        assertDeletedOnceAndHeld();
    }

    @Test
    void aSaveWaitsForADeleteInProgressAndThenFindsNoPage() {
        whileTheHandleIsHeld(() -> bioPageService.update(saving(button(instagram, "Instagram"))));

        bioPageService.delete();

        assertTheOtherRequestWaitedForTheLock();
        assertThatThrownBy(() -> bioPageService.update(saving(button(instagram, "Instagram"))))
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);
        assertDeletedOnceAndHeld();
    }

    @Test
    void aSecondDeleteWaitsForTheFirstAndThenFindsNoPage() {
        whileTheHandleIsHeld(() -> bioPageService.delete());

        bioPageService.delete();

        assertTheOtherRequestWaitedForTheLock();
        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);
        assertDeletedOnceAndHeld();
    }

    @Test
    void aLinkDeletedWhileASaveAddsItIs409() {
        whileTheButtonsAreRead(() -> jdbcTemplate.update("delete from url where id = ?", instagram.getId()));

        assertThatThrownBy(() -> bioPageService.update(saving(button(instagram, "Instagram"))))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(PAGE_CHANGED);

        assertThat(otherRequestRan).isTrue();
        assertThat(otherRequestFailure.get()).isNull();
        assertThat(buttonsOnThePage()).isZero();
    }

    @Test
    void aLinkDeletedWhileASaveKeepsItsButtonIs409() {
        bioPageService.update(saving(button(instagram, "Instagram")));
        afterTheButtonsAreRead(() -> jdbcTemplate.update("delete from url where id = ?", instagram.getId()));

        assertThatThrownBy(() -> bioPageService.update(saving(button(instagram, "Insta"))))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(PAGE_CHANGED);

        assertThat(otherRequestRan).isTrue();
        assertThat(otherRequestFailure.get()).isNull();
        assertThat(buttonsOnThePage()).isZero();
    }

    private void whileTheButtonsAreRead(final Runnable request) {
        doAnswer(invocation -> {
            runTheOtherRequestOnce(request);
            return invocation.callRealMethod();
        }).when(bioPageResponses).buttonsOf(any(BioPage.class));
    }

    private void afterTheButtonsAreRead(final Runnable request) {
        doAnswer(invocation -> {
            final var buttons = invocation.callRealMethod();
            runTheOtherRequestOnce(request);
            return buttons;
        }).when(bioPageResponses).buttonsOf(any(BioPage.class));
    }

    private void whileTheHandleIsHeld(final Runnable request) {
        doAnswer(invocation -> {
            runTheOtherRequestOnce(request);
            return invocation.callRealMethod();
        }).when(bioHandles).hold(eq("ana"), isNull());
    }

    private void runTheOtherRequestOnce(final Runnable request) {
        if (otherRequestRan.compareAndSet(false, true)) {
            otherRequestFailure.set(catchThrowable(() -> otherRequest.executeWithoutResult(status -> request.run())));
        }
    }

    private void assertTheOtherRequestWaitedForTheLock() {
        assertThat(otherRequestRan).isTrue();
        assertThat(otherRequestFailure.get()).isInstanceOf(PessimisticLockingFailureException.class);
    }

    private void assertDeletedOnceAndHeld() {
        assertThat(bioPageRepository.findById(page.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForList("select handle from bio_handle_hold where page_id is null", String.class))
                .containsExactly("ana");
    }

    private Long buttonsOnThePage() {
        return jdbcTemplate.queryForObject("select count(*) from bio_page_button where page_id = ?", Long.class, page.getId());
    }

    private static BioPageButtonDto button(final Url url, final String title) {
        return BioPageButtonDto.builder().urlId(url.getId()).title(title).build();
    }

    private static BioPageUpdateDto saving(final BioPageButtonDto... buttons) {
        return BioPageUpdateDto.builder()
                .displayName("Ana")
                .bio("")
                .avatarKind(BioAvatarKind.INITIALS)
                .accent(BioAccent.BLUE)
                .theme(BioTheme.AUTO)
                .buttons(List.of(buttons))
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
    @Import({ClockConfiguration.class, BioHandles.class, BioPageLock.class, BioPageResponses.class, DefaultBioPageService.class})
    static class TestApplication {
    }

}
