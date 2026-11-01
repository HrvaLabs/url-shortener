package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ResetLinkExpiredException;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.security.ApiKeyTokens;
import cc.hrva.urlshortener.service.impl.DefaultResetTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultResetTokenServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final String EXPIRED = "This reset link has expired or was already used. Ask for a new one.";

    private ResetTokenService resetTokenService;

    @Mock
    private AppProperties appProperties;

    @Mock
    private ResetTokenRepository resetTokenRepository;

    @BeforeEach
    void setUp() {
        this.resetTokenService = new DefaultResetTokenService(appProperties, resetTokenRepository,
                Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldDeactivateExpiredPasswordResetTokens() {
        final var token = ResetToken.builder().id(1L).active(true).build();
        when(resetTokenRepository.findByExpirationDateIsLessThanEqualAndActiveTrue(any(LocalDateTime.class)))
                .thenReturn(Collections.singletonList(token));
        when(resetTokenRepository.saveAll(any())).thenReturn(Collections.singletonList(token));

        resetTokenService.deactivateExpiredPasswordResetTokens();

        assertThat(token.isActive()).isFalse();
    }

    @Test
    void shouldDeactivateActiveResetTokenIfExists() {
        final var user = User.builder().id(1L).build();
        final var token = ResetToken.builder().id(1L).active(true).build();
        when(resetTokenRepository.findByUserAndActiveTrue(user)).thenReturn(Collections.singletonList(token));
        when(resetTokenRepository.saveAll(any())).thenReturn(Collections.singletonList(token));

        resetTokenService.deactivateActiveResetTokenIfExists(user);

        assertThat(token.isActive()).isFalse();
    }

    @Test
    void shouldDeleteExpiredPasswordResetTokens() {
        final var token = ResetToken.builder().id(1L).active(false).build();
        when(appProperties.getIpRetentionDurationInHours()).thenReturn(24L);
        when(resetTokenRepository.findByExpirationDateIsLessThanEqualAndActiveFalse(any(LocalDateTime.class)))
                .thenReturn(Collections.singletonList(token));

        resetTokenService.deleteExpiredPasswordResetTokens();

        verify(resetTokenRepository).deleteAll(Collections.singletonList(token));
    }

    @Test
    void aNewTokenIsLongRandomAndUrlSafeAndOnlyItsHashIsStoredForAnHour() {
        final var user = User.builder().id(1L).build();
        when(appProperties.getResetTokenExpirationInHours()).thenReturn(1L);
        when(resetTokenRepository.save(any(ResetToken.class))).thenAnswer(inv -> inv.getArgument(0));

        final var token = resetTokenService.createTokenForUser(user);

        final var saved = ArgumentCaptor.forClass(ResetToken.class);
        verify(resetTokenRepository).save(saved.capture());
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(saved.getValue().getUser()).isEqualTo(user);
        assertThat(saved.getValue().getTokenHash()).isEqualTo(ApiKeyTokens.hash(token)).doesNotContain(token);
        assertThat(saved.getValue().getExpirationDate()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void everyTokenIsDifferent() {
        when(appProperties.getResetTokenExpirationInHours()).thenReturn(1L);
        when(resetTokenRepository.save(any(ResetToken.class))).thenAnswer(inv -> inv.getArgument(0));
        final var user = User.builder().id(1L).build();

        final var tokens = new HashSet<String>();
        IntStream.range(0, 50).forEach(i -> tokens.add(resetTokenService.createTokenForUser(user)));

        assertThat(tokens).hasSize(50);
    }

    @Test
    void aTokenIsClaimedByItsHashOnceAndOnlyBeforeItExpires() {
        final var user = User.builder().id(1L).build();
        final var token = ResetToken.builder().id(7L).user(user).active(true).build();
        when(resetTokenRepository.findByUserAndTokenHashAndActiveTrue(user, ApiKeyTokens.hash("the-token"))).thenReturn(Optional.of(token));
        when(resetTokenRepository.claim(7L, NOW)).thenReturn(1);

        assertThat(resetTokenService.claim(user, "the-token")).isEqualTo(token);
    }

    @Test
    void anUnknownOrUsedTokenIsRefused() {
        final var user = User.builder().id(1L).build();
        when(resetTokenRepository.findByUserAndTokenHashAndActiveTrue(user, ApiKeyTokens.hash("the-token"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resetTokenService.claim(user, "the-token"))
                .isInstanceOf(ResetLinkExpiredException.class)
                .hasMessage(EXPIRED);
        verify(resetTokenRepository, never()).claim(any(), any());
    }

    @Test
    void aTokenUsedOrExpiredSinceItWasReadIsRefused() {
        final var user = User.builder().id(1L).build();
        when(resetTokenRepository.findByUserAndTokenHashAndActiveTrue(user, ApiKeyTokens.hash("the-token")))
                .thenReturn(Optional.of(ResetToken.builder().id(7L).user(user).active(true).build()));
        when(resetTokenRepository.claim(7L, NOW)).thenReturn(0);

        assertThatThrownBy(() -> resetTokenService.claim(user, "the-token"))
                .isInstanceOf(ResetLinkExpiredException.class)
                .hasMessage(EXPIRED);
    }

}
