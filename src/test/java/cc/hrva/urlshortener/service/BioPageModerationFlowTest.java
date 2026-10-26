package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.BioPageReviewRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageLock;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.bio.BioPageTextSignals;
import cc.hrva.urlshortener.service.impl.DefaultBioPageReviewService;
import cc.hrva.urlshortener.service.impl.DefaultBioPageService;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@RecordApplicationEvents
@SpringBootTest(
        classes = BioPageModerationFlowTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:bio-page-moderation-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageModerationFlowTest {

    @Autowired
    private BioPageService bioPageService;

    @Autowired
    private BioPageReviewService bioPageReviewService;

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    private User ana;

    @BeforeEach
    void setUp() {
        ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).active(true).build());
        when(userService.getUserFromToken()).thenReturn(ana);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from bio_handle_hold");
        jdbcTemplate.update("delete from bio_page where user_id = ?", ana.getId());
        jdbcTemplate.update("delete from user_account where id = ?", ana.getId());
    }

    @Test
    void aSuspiciousHandleOpensOneReviewThatLaterFlagsAndReportsExtend() {
        final var created = bioPageService.create(new BioPageCreateDto("PayPal-Help"));

        assertThat(reviewsOf(created.id())).containsExactly(Map.of("REASONS", "handle imitates paypal", "SYSTEM_FLAGGED", true));
        assertThat(events.stream(AdminAlertEvent.BioPageReviewOpened.class))
                .extracting(opened -> opened.page().pageId(), opened -> opened.page().handle(), opened -> opened.page().ownerId(),
                        AdminAlertEvent.BioPageReviewOpened::reasons)
                .containsExactly(tuple(created.id(), "paypal-help", ana.getId(), List.of("handle imitates paypal")));

        bioPageService.update(saving("Ana", "Verify your wallet"));
        bioPageReviewService.report(bioPageRepository.findByHandleWithOwner("paypal-help").orElseThrow(), ReportCategory.SPAM, "fake shop");

        assertThat(reviewsOf(created.id())).containsExactly(Map.of(
                "REASONS", "handle imitates paypal\nbio has a login or verification lure\nreported by a visitor: spam: fake shop",
                "SYSTEM_FLAGGED", true));
        assertThat(events.stream(AdminAlertEvent.BioPageReviewOpened.class)).hasSize(1);
        assertThat(events.stream(AdminAlertEvent.BioPageReported.class))
                .extracting(AdminAlertEvent.BioPageReported::reviewOpened)
                .containsExactly(false);
    }

    @Test
    void aCleanPageOpensNothing() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));
        bioPageService.update(saving("Ana Horvat", "Photos from Zagreb"));
        bioPageService.changeHandle(new BioHandleDto("ana-horvat"));

        assertThat(reviewsOf(created.id())).isEmpty();
        assertThat(events.stream(AdminAlertEvent.class)).isEmpty();
    }

    @Test
    void aHandleChangeToABrandOpensAReview() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));
        bioPageService.update(saving("Ana Horvat", ""));

        bioPageService.changeHandle(new BioHandleDto("netflix-ana"));

        assertThat(reviewsOf(created.id())).containsExactly(Map.of("REASONS", "handle imitates netflix", "SYSTEM_FLAGGED", true));
    }

    @Test
    void aPageTheAdminHidIsNotFlaggedWhenItsOwnerSaves() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));
        jdbcTemplate.update("update bio_page set hidden_at = ?, hidden_reason = 'Spam' where id = ?", LocalDateTime.of(2026, 10, 26, 9, 0), created.id());

        bioPageService.update(saving("PayPal Support", ""));

        assertThat(reviewsOf(created.id())).isEmpty();
        assertThat(events.stream(AdminAlertEvent.class)).isEmpty();
    }

    @Test
    void aReportOnAPageDeletedMeanwhileFailsOnTheReviewsPageKeyAndAlertsNothing() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));
        final var page = bioPageRepository.findByHandleWithOwner("ana").orElseThrow();
        jdbcTemplate.update("delete from bio_page where id = ?", created.id());

        assertThatThrownBy(() -> bioPageReviewService.report(page, ReportCategory.SPAM, null))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, exception ->
                        assertThat(ExceptionUtils.throwableOfType(exception, ConstraintViolationException.class).getConstraintName())
                                .containsIgnoringCase("fk_bio_page_review_page"));
        assertThat(reviewsOf(created.id())).isEmpty();
        assertThat(events.stream(AdminAlertEvent.class)).isEmpty();
    }

    private List<Map<String, Object>> reviewsOf(final Long pageId) {
        return jdbcTemplate.queryForList("select reasons, system_flagged from bio_page_review where page_id = ? order by id", pageId);
    }

    private static BioPageUpdateDto saving(final String displayName, final String bio) {
        return BioPageUpdateDto.builder()
                .displayName(displayName)
                .bio(bio)
                .avatarKind(BioAvatarKind.INITIALS)
                .accent(BioAccent.BLUE)
                .theme(BioTheme.AUTO)
                .buttons(List.of())
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
                            BioPageReviewRepository.class,
                            BioHandleHoldRepository.class,
                            UrlRepository.class,
                            UserRepository.class,
                            LinkReviewRepository.class}))
    @Import({ClockConfiguration.class, BioHandles.class, BioPageLock.class, BioPageResponses.class, BioPageTextSignals.class,
            DefaultBioPageReviewService.class, DefaultBioPageService.class})
    static class TestApplication {

        @Bean
        SuspiciousUrlService suspiciousUrlService() {
            final var appProperties = new AppProperties();
            appProperties.setServerUrl("https://hrva.cc");
            return new DefaultSuspiciousUrlService(appProperties);
        }

    }

}
