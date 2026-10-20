package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultSafeBrowsingRecheckService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSafeBrowsingRecheckServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private SafeBrowsingService safeBrowsingService;

    @Mock
    private SendingEmailService sendingEmailService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    private SafeBrowsingRecheckService recheckService;

    private Map<Long, Url> urls;

    @BeforeEach
    void setUp() {
        recheckService = new DefaultSafeBrowsingRecheckService(urlRepository, safeBrowsingService, sendingEmailService, cacheManager);
        urls = LongStream.rangeClosed(1, 45)
                .mapToObj(id -> Url.builder().id(id).shortUrl("s" + id).longUrl("https://site" + id + ".example").active(true).build())
                .collect(Collectors.toMap(Url::getId, Function.identity()));
        when(urlRepository.findActiveIds()).thenReturn(new ArrayList<>(urls.keySet()));
        when(urlRepository.findAllById(anyList())).thenAnswer(invocation -> {
            final List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(urls::get).toList();
        });
        when(safeBrowsingService.checkUrlsForThreats(anyString())).thenReturn(List.of());
    }

    @Test
    void checksEveryActiveLinkEvenWhenEarlierOnesGetDeactivated() {
        when(safeBrowsingService.checkUrlsForThreats("https://site1.example")).thenReturn(List.of("https://site1.example"));
        when(safeBrowsingService.checkUrlsForThreats("https://site2.example")).thenReturn(List.of("https://site2.example"));
        when(cacheManager.getCache("urls")).thenReturn(cache);

        recheckService.recheckActiveUrls();

        assertThat(urls.values()).allSatisfy(url -> assertThat(url.getLastSafeBrowsingCheck()).isNotNull());
        assertThat(urls.values().stream().filter(url -> !url.isActive()).map(Url::getShortUrl)).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void deactivatesAFlaggedLinkEvictsItAndTellsItsOwner() {
        final var owner = User.builder().email("owner@example.com").build();
        urls.get(7L).setOwner(owner);
        when(safeBrowsingService.checkUrlsForThreats("https://site7.example")).thenReturn(List.of("https://site7.example"));
        when(cacheManager.getCache("urls")).thenReturn(cache);

        recheckService.recheckActiveUrls();

        assertThat(urls.get(7L).isActive()).isFalse();
        verify(cache).evict("s7");
        verify(sendingEmailService).sendEmailUrlMalwareDetected(eq(owner), eq(urls.get(7L)), anyString());
    }

    @Test
    void skipsALinkThatWasDeactivatedWhileTheScanRan() {
        urls.get(3L).setActive(false);

        recheckService.recheckActiveUrls();

        verify(safeBrowsingService, never()).checkUrlsForThreats("https://site3.example");
    }

}
