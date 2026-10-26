package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageReasonDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.BioPageReviewRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageLock;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.bio.BioPageTextSignals;
import cc.hrva.urlshortener.service.impl.DefaultAdminBioPageService;
import cc.hrva.urlshortener.service.impl.DefaultBioPageReviewService;
import cc.hrva.urlshortener.service.impl.DefaultBioPageService;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = BioPageOwnerFlowTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:bio-page-owner-flow;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class BioPageOwnerFlowTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final String OUTDATED_COPY =
            "Your bio page changed in another tab or window. We loaded the latest version; check your changes and save again.";

    @Autowired
    private BioPageService bioPageService;

    @Autowired
    private AdminBioPageService adminBioPageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

    private User ana;
    private User admin;

    @BeforeEach
    void setUp() {
        ana = userRepository.saveAndFlush(User.builder().email("ana@example.com").password("correct horse battery").apiKeySlots(4L).active(true).build());
        admin = userRepository.saveAndFlush(User.builder().email("admin@hrva.cc").password("correct horse battery").apiKeySlots(10L).active(true).build());
        when(userService.getUserFromToken()).thenReturn(ana);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from bio_handle_hold");
        jdbcTemplate.update("delete from bio_page_review");
        jdbcTemplate.update("delete from bio_page");
        jdbcTemplate.update("delete from user_account");
    }

    @Test
    void eachSaveFromThePreviousAnswerGoesThrough() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));

        final var first = bioPageService.update(savingFrom(created, "Photos from Zagreb"));
        final var second = bioPageService.update(savingFrom(first, "Photos from Split"));
        final var third = bioPageService.update(savingFrom(bioPageService.mine(), "Photos from Rijeka"));

        assertThat(storedBio(created.id())).isEqualTo("Photos from Rijeka");
        assertThat(second.updatedAt()).isAfterOrEqualTo(first.updatedAt());
        assertThat(bioPageService.mine().updatedAt()).isEqualTo(third.updatedAt());
        assertThat(storedUpdatedAt(created.id())).isEqualTo(third.updatedAt());
    }

    @Test
    void aSaveFromAnOutdatedCopyIs409AndWritesNothing() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));
        final var fromTabA = bioPageService.update(savingFrom(created, "Saved in tab A"));

        assertThatThrownBy(() -> bioPageService.update(savingFrom(created, "Saved in tab B")))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(OUTDATED_COPY);

        assertThat(storedBio(created.id())).isEqualTo("Saved in tab A");
        assertThat(storedUpdatedAt(created.id())).isEqualTo(fromTabA.updatedAt());
    }

    @Test
    void aSaveWithoutACopyTimestampIsNotChecked() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));
        bioPageService.update(savingFrom(created, "Saved in tab A"));
        final var withoutTimestamp = savingFrom(created, "Saved by an older app");
        withoutTimestamp.setExpectedUpdatedAt(null);

        bioPageService.update(withoutTimestamp);

        assertThat(storedBio(created.id())).isEqualTo("Saved by an older app");
    }

    @Test
    void anOwnerSaveFromBeforeAnAdminHidThePageIs409() {
        final var created = bioPageService.create(new BioPageCreateDto("ana"));
        final var beforeTheHide = bioPageService.update(savingFrom(created, "Photos from Zagreb"));
        when(userService.getUserFromToken()).thenReturn(admin);
        adminBioPageService.hide(created.id(), new BioPageReasonDto("Impersonation"));
        when(userService.getUserFromToken()).thenReturn(ana);

        assertThatThrownBy(() -> bioPageService.update(savingFrom(beforeTheHide, "Saved after the hide")))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(OUTDATED_COPY);
        assertThat(storedBio(created.id())).isEqualTo("Photos from Zagreb");

        final var reloaded = bioPageService.mine();
        assertThat(reloaded.hidden()).isTrue();
        bioPageService.update(savingFrom(reloaded, "Saved after a reload"));
        assertThat(storedBio(created.id())).isEqualTo("Saved after a reload");
    }

    private BioPageUpdateDto savingFrom(final BioPageResponse copy, final String bio) {
        final var updatedAt = jsonMapper.readTree(jsonMapper.writeValueAsString(copy)).get("updatedAt").asString();
        return jsonMapper.readValue("""
                {"displayName":"Ana","bio":"%s","avatarKind":"INITIALS","accent":"BLUE","theme":"AUTO","buttons":[],"expectedUpdatedAt":"%s"}"""
                .formatted(bio, updatedAt), BioPageUpdateDto.class);
    }

    private String storedBio(final Long pageId) {
        return jdbcTemplate.queryForObject("select bio from bio_page where id = ?", String.class, pageId);
    }

    private LocalDateTime storedUpdatedAt(final Long pageId) {
        return jdbcTemplate.queryForObject("select updated_at from bio_page where id = ?", LocalDateTime.class, pageId);
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
    @Import({BioHandles.class, BioPageLock.class, BioPageResponses.class, BioPageTextSignals.class,
            DefaultBioPageReviewService.class, DefaultBioPageService.class, DefaultAdminBioPageService.class})
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        }

        @Bean
        SuspiciousUrlService suspiciousUrlService() {
            final var appProperties = new AppProperties();
            appProperties.setServerUrl("https://hrva.cc");
            return new DefaultSuspiciousUrlService(appProperties);
        }

    }

}
