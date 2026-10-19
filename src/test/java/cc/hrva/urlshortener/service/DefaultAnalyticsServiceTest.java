package cc.hrva.urlshortener.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.IPAddress;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.IPAddressRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAnalyticsServiceTest {

    private AnalyticsService analyticsService;

    @Mock
    private UserService userService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private IPAddressRepository ipAddressRepository;

    private User owner;

    @BeforeEach
    void setUp() {
        this.analyticsService = new DefaultAnalyticsService(userService, urlRepository, ipAddressRepository);
        this.owner = User.builder().id(1L).email("owner@example.com")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
    }

    @Test
    void shouldAggregateOverview() {
        final var now = LocalDateTime.now();
        final var active = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").visits(10L).active(true).build();
        final var expired = Url.builder().id(2L).shortUrl("b").longUrl("https://b.com").visits(50L).active(false)
                .expirationDate(now.minusDays(1)).build();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findByOwner(owner)).thenReturn(List.of(active, expired));

        final var overview = analyticsService.getOverview();

        assertThat(overview.totalUrls()).isEqualTo(2);
        assertThat(overview.activeUrls()).isEqualTo(1);
        assertThat(overview.expiredUrls()).isEqualTo(1);
        assertThat(overview.totalVisits()).isEqualTo(60);
        assertThat(overview.topUrls()).hasSize(2);
        assertThat(overview.topUrls().get(0).visits()).isEqualTo(50);
    }

    @Test
    void shouldBuildUrlAnalyticsForOwner() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").visits(3L).active(true).owner(owner).build();
        final var ip1 = IPAddress.builder().id(1L).visits(2L).createDate(LocalDateTime.now()).build();
        final var ip2 = IPAddress.builder().id(2L).visits(5L).createDate(LocalDateTime.now().minusDays(2)).build();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(ipAddressRepository.findAllByUrl(url)).thenReturn(List.of(ip1, ip2));

        final var analytics = analyticsService.getUrlAnalytics(7L);

        assertThat(analytics.visits()).isEqualTo(3);
        assertThat(analytics.uniqueRecentVisitors()).isEqualTo(2);
        assertThat(analytics.totalRecentClicks()).isEqualTo(7);
        assertThat(analytics.status()).isEqualTo("ACTIVE");
        assertThat(analytics.dailyNewVisitors()).hasSize(30);
        assertThat(analytics.dailyNewVisitors().get(29).count()).isEqualTo(1);
    }

    @Test
    void shouldRejectUrlAnalyticsForNonOwner() {
        final var otherOwner = User.builder().id(99L).build();
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").active(true).owner(otherOwner).build();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> analyticsService.getUrlAnalytics(7L))
                .isInstanceOf(NoAuthorizationException.class);
    }

    @Test
    void shouldAllowAdminToViewAnyUrlAnalytics() {
        final var admin = User.builder().id(2L).email("admin@example.com")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_ADMIN").build()))
                .build();
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").visits(1L).active(true)
                .owner(User.builder().id(99L).build()).build();
        when(userService.getUserFromToken()).thenReturn(admin);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(ipAddressRepository.findAllByUrl(url)).thenReturn(Collections.emptyList());

        final var analytics = analyticsService.getUrlAnalytics(7L);

        assertThat(analytics.id()).isEqualTo(7L);
        assertThat(analytics.uniqueRecentVisitors()).isZero();
    }

    @Test
    void shouldFailUrlAnalyticsWhenUrlMissing() {
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analyticsService.getUrlAnalytics(7L))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void ownerOnlyStatsRejectAdminReadingForeignUrl() {
        final var admin = User.builder().id(2L).email("admin@example.com")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_ADMIN").build()))
                .build();
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").visits(1L).active(true)
                .owner(User.builder().id(99L).build()).build();
        when(userService.getUserFromToken()).thenReturn(admin);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> analyticsService.getUrlAnalyticsForOwner(7L))
                .isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(ipAddressRepository);
    }

    @Test
    void ownerOnlyStatsRejectUrlWithoutOwner() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").active(true).build();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> analyticsService.getUrlAnalyticsForOwner(7L))
                .isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(ipAddressRepository);
    }

    @Test
    void ownerOnlyStatsBuildAnalyticsForOwner() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").visits(3L).active(true).owner(owner).build();
        final var ip = IPAddress.builder().id(1L).visits(4L).createDate(LocalDateTime.now()).build();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(ipAddressRepository.findAllByUrl(url)).thenReturn(List.of(ip));

        final var analytics = analyticsService.getUrlAnalyticsForOwner(7L);

        assertThat(analytics.id()).isEqualTo(7L);
        assertThat(analytics.visits()).isEqualTo(3);
        assertThat(analytics.uniqueRecentVisitors()).isEqualTo(1);
        assertThat(analytics.totalRecentClicks()).isEqualTo(4);
    }

    @Test
    void ownerOnlyStatsFailWhenUrlMissing() {
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analyticsService.getUrlAnalyticsForOwner(7L))
                .isInstanceOf(UrlNotFoundException.class);
    }
}
