package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultLinkDigestService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLinkDigestServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private SuspiciousUrlService suspiciousUrlService;

    @Mock
    private SendingEmailService sendingEmailService;

    private LinkDigestService linkDigestService;

    @BeforeEach
    void setUp() {
        linkDigestService = new DefaultLinkDigestService(urlRepository, suspiciousUrlService, sendingEmailService);
    }

    private static Url link(final String shortUrl, final String longUrl, final User owner) {
        return Url.builder().shortUrl(shortUrl).longUrl(longUrl).owner(owner).build();
    }

    @SuppressWarnings("unchecked")
    private List<LinkDigestEntry> sentEntries() {
        final ArgumentCaptor<List<LinkDigestEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(sendingEmailService).sendLinkDigestToAdmin(captor.capture());
        return captor.getValue();
    }

    @Test
    void listsNewLinksFromNewAccountsAndSuspiciousLinksFromAnyone() {
        final var newcomer = User.builder().email("raulsado50w@gmail.com").createDate(LocalDateTime.now().minusDays(1)).build();
        final var regular = User.builder().email("karlo@hrva.cc").createDate(LocalDateTime.now().minusYears(2)).build();
        when(urlRepository.findByCreateDateAfter(any(LocalDateTime.class))).thenReturn(List.of(
                link("njuskaio_verify", "https://njuskaio.cam/a/MAUB3", newcomer),
                link("gableci", "https://gableci.hrva.cc", regular),
                link("v0DB3", "https://findfavoritedate.life/?u=1", regular)));
        when(suspiciousUrlService.findReasons(anyString())).thenReturn(List.of());
        when(suspiciousUrlService.findReasons("https://njuskaio.cam/a/MAUB3")).thenReturn(List.of("imitates njuskalo"));
        when(suspiciousUrlService.findReasons("https://findfavoritedate.life/?u=1")).thenReturn(List.of("high-risk domain ending .life"));

        linkDigestService.sendDailyDigest();

        assertThat(sentEntries()).containsExactly(
                new LinkDigestEntry("njuskaio_verify", "https://njuskaio.cam/a/MAUB3", "raulsado50w@gmail.com",
                        List.of("account created in the last 7 days", "imitates njuskalo")),
                new LinkDigestEntry("v0DB3", "https://findfavoritedate.life/?u=1", "karlo@hrva.cc",
                        List.of("high-risk domain ending .life")));
    }

    @Test
    void looksBackOneDay() {
        final var since = ArgumentCaptor.forClass(LocalDateTime.class);
        when(urlRepository.findByCreateDateAfter(since.capture())).thenReturn(List.of());

        linkDigestService.sendDailyDigest();

        assertThat(since.getValue()).isBetween(LocalDateTime.now().minusHours(25), LocalDateTime.now().minusHours(23));
    }

    @Test
    void sendsNothingWhenThereIsNothingToReview() {
        final var regular = User.builder().email("karlo@hrva.cc").createDate(LocalDateTime.now().minusYears(2)).build();
        when(urlRepository.findByCreateDateAfter(any(LocalDateTime.class))).thenReturn(List.of(link("gableci", "https://gableci.hrva.cc", regular)));
        when(suspiciousUrlService.findReasons(anyString())).thenReturn(List.of());

        linkDigestService.sendDailyDigest();

        verify(sendingEmailService, never()).sendLinkDigestToAdmin(anyList());
    }

}
