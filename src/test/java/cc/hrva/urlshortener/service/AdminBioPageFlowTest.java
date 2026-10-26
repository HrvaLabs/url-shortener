package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.bio.AdminBioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioHandleRevokeDto;
import cc.hrva.urlshortener.dto.bio.BioPageReasonDto;
import cc.hrva.urlshortener.dto.bio.BioPageReviewResolveDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioPageReviewAction;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.BioPageReviewRepository;
import cc.hrva.urlshortener.repository.ReservedAliasRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageLock;
import cc.hrva.urlshortener.service.impl.DefaultAdminBioPageService;
import cc.hrva.urlshortener.service.impl.DefaultAuditLogService;
import cc.hrva.urlshortener.service.impl.DefaultBioPageReviewService;
import cc.hrva.urlshortener.service.impl.DefaultReservedAliasService;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

@RecordApplicationEvents
@SpringBootTest(
        classes = AdminBioPageFlowTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:admin-bio-page-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class AdminBioPageFlowTest {

    private static final String ADMIN_EMAIL = "admin@hrva.cc";

    @Autowired
    private AdminBioPageService adminBioPageService;

    @Autowired
    private BioPageReviewService bioPageReviewService;

    @Autowired
    private ReservedAliasService reservedAliasService;

    @Autowired
    private BioHandles bioHandles;

    @Autowired
    private BioPageRepository bioPageRepository;

    @Autowired
    private BioPageButtonRepository bioPageButtonRepository;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @MockitoBean
    private UserService userService;

    private User ana;
    private BioPage page;

    @BeforeEach
    void setUp() {
        final var admin = userRepository.saveAndFlush(User.builder().email(ADMIN_EMAIL).password("correct horse battery").apiKeySlots(10L).active(true).build());
        when(userService.getUserFromToken()).thenReturn(admin);
        ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).active(true).build());
        page = bioPageRepository.saveAndFlush(BioPage.builder().owner(ana).handle("paypal-help").displayName("Ana Horvat").bio("Photos").build());
        final var shop = urlRepository.saveAndFlush(Url.builder().shortUrl("ana-shop").longUrl("https://example.org/shop").owner(ana).build());
        final var draft = urlRepository.saveAndFlush(Url.builder().shortUrl("ana-draft").longUrl("https://example.org/draft").owner(ana).build());
        bioPageButtonRepository.saveAndFlush(BioPageButton.builder().page(page).url(shop).title("Shop").sortOrder(0).visible(true).build());
        bioPageButtonRepository.saveAndFlush(BioPageButton.builder().page(page).url(draft).title("Draft").sortOrder(1).visible(false).build());
        bioPageReviewService.flag(page, List.of("handle imitates paypal"));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from bio_handle_hold");
        jdbcTemplate.update("delete from reserved_alias");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from bio_page");
        jdbcTemplate.update("delete from url");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void searchFindsThePageWithItsCountsAndOpenReview() {
        final var found = adminBioPageService.search("PAYPAL", false, PageRequest.of(0, 20));

        assertThat(found.getContent())
                .extracting(AdminBioPageResponse::id, AdminBioPageResponse::handle, AdminBioPageResponse::ownerEmail, AdminBioPageResponse::buttons,
                        AdminBioPageResponse::visibleButtons, AdminBioPageResponse::reviewOpen)
                .containsExactly(tuple(page.getId(), "paypal-help", "ana@example.com", 2L, 1L, true));
        assertThat(adminBioPageService.search("ana@example", false, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        assertThat(adminBioPageService.search("nobody", false, PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(adminBioPageService.search(null, true, PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    void hidingClosesTheReviewWithoutAnAlertAndUnhidingPutsThePageBack() {
        final var alertsBefore = events.stream(AdminAlertEvent.class).count();

        final var hidden = adminBioPageService.hide(page.getId(), new BioPageReasonDto("Impersonation"));

        assertThat(hidden.hidden()).isTrue();
        assertThat(hidden.reviewOpen()).isFalse();
        assertThat(events.stream(AdminAlertEvent.class).count()).isEqualTo(alertsBefore);
        assertThat(jdbcTemplate.queryForList("select resolution, resolved_by from bio_page_review where page_id = ?", page.getId()))
                .containsExactly(Map.of("RESOLUTION", "HIDDEN", "RESOLVED_BY", ADMIN_EMAIL));
        assertThat(adminBioPageService.search(null, true, PageRequest.of(0, 20)).getContent())
                .extracting(AdminBioPageResponse::id, AdminBioPageResponse::hiddenReason, AdminBioPageResponse::hiddenBy)
                .containsExactly(tuple(page.getId(), "Impersonation", ADMIN_EMAIL));
        assertThat(bioPageRepository.findByHandleWithOwner("paypal-help").orElseThrow().isPublic()).isFalse();

        adminBioPageService.unhide(page.getId());

        assertThat(bioPageRepository.findByHandleWithOwner("paypal-help").orElseThrow().isPublic()).isTrue();
    }

    @Test
    void resolvingWithHideHidesThePageAndASecondResolveIsRefused() {
        adminBioPageService.resolveReview(page.getId(), new BioPageReviewResolveDto(BioPageReviewAction.HIDE, "Phishing or scam"));

        assertThat(bioPageRepository.findById(page.getId()).orElseThrow().getHiddenReason()).isEqualTo("Phishing or scam");
        assertThatThrownBy(() -> adminBioPageService.resolveReview(page.getId(), new BioPageReviewResolveDto(BioPageReviewAction.OK, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("This bio page is no longer in the review queue.");
    }

    @Test
    void renamingHoldsTheOldHandleForThePage() {
        adminBioPageService.changeHandle(page.getId(), new BioHandleDto("ana-horvat"));

        assertThat(bioPageRepository.findById(page.getId()).orElseThrow().getHandle()).isEqualTo("ana-horvat");
        assertThat(jdbcTemplate.queryForList("select handle, page_id from bio_handle_hold"))
                .containsExactly(Map.of("HANDLE", "paypal-help", "PAGE_ID", page.getId()));
    }

    @Test
    void revokingReservesTheHandleSoNoPageCanTakeIt() {
        final var revoked = adminBioPageService.revokeHandle(page.getId(), new BioHandleRevokeDto("Impersonation", null));

        assertThat(revoked.handle()).isNull();
        assertThat(revoked.handleRevokedReason()).isEqualTo("Impersonation");
        assertThat(jdbcTemplate.queryForList("select alias, reason, created_by from reserved_alias"))
                .containsExactly(Map.of("ALIAS", "paypal-help", "REASON", "Revoked bio page handle: Impersonation", "CREATED_BY", ADMIN_EMAIL));
        assertThat(jdbcTemplate.queryForList("select action, failed, target_identifier from audit_log"))
                .containsExactly(Map.of("ACTION", "RESERVE_ALIAS", "FAILED", false, "TARGET_IDENTIFIER", "paypal-help"));
        assertThat(bioHandles.unavailableReason("paypal-help", null)).contains("This handle is reserved. Try another.");
        assertThatThrownBy(() -> adminBioPageService.revokeHandle(page.getId(), new BioHandleRevokeDto("Again", null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("This page has no handle.");
    }

    @Test
    void aRevokeFromAStaleListAfterARenameChangesNothing() {
        adminBioPageService.changeHandle(page.getId(), new BioHandleDto("ana-horvat"));

        assertThatThrownBy(() -> adminBioPageService.revokeHandle(page.getId(), new BioHandleRevokeDto("Impersonation", "paypal-help")))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage("This handle changed. Reload and try again.");

        final var unchanged = bioPageRepository.findById(page.getId()).orElseThrow();
        assertThat(unchanged.getHandle()).isEqualTo("ana-horvat");
        assertThat(unchanged.getHandleRevokedReason()).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from reserved_alias", Long.class)).isZero();
    }

    @Test
    void revokingAHandleThatIsAlreadyReservedStillRevokesWithoutAFailedAuditRow() {
        reservedAliasService.reserve(new ReservedAliasDto("paypal-help", "Spring promo"), ADMIN_EMAIL);

        adminBioPageService.revokeHandle(page.getId(), new BioHandleRevokeDto("Impersonation", null));

        assertThat(bioPageRepository.findById(page.getId()).orElseThrow().getHandle()).isNull();
        assertThat(jdbcTemplate.queryForList("select reason from reserved_alias", String.class)).containsExactly("Spring promo");
        assertThat(jdbcTemplate.queryForList("select failed from audit_log", Boolean.class)).containsExactly(false);
    }

    @Test
    void anAdminCanGiveARevokedPageANewHandle() {
        adminBioPageService.revokeHandle(page.getId(), new BioHandleRevokeDto("Impersonation", null));

        final var renamed = adminBioPageService.changeHandle(page.getId(), new BioHandleDto("ana"));

        assertThat(renamed.handle()).isEqualTo("ana");
        assertThat(renamed.handleRevokedReason()).isNull();
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
                            ReservedAliasRepository.class,
                            AuditLogRepository.class,
                            UrlRepository.class,
                            UserRepository.class}))
    @Import({ClockConfiguration.class, BioHandles.class, BioPageLock.class, DefaultBioPageReviewService.class, DefaultReservedAliasService.class,
            DefaultAuditLogService.class, DefaultAdminBioPageService.class})
    static class TestApplication {
    }

}
