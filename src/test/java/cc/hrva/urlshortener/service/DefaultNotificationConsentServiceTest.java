package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.notification.NotificationConsentResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.service.impl.DefaultNotificationConsentService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultNotificationConsentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T08:15:02.678Z");
    private static final LocalDateTime GRANTED_AT = LocalDateTime.of(2026, 10, 27, 8, 15, 2);
    private static final String SETTINGS_KEY = "notify-settings:user:7";
    private static final String NOT_AN_EMAIL_TYPE = "Notification type must be DEAD_LINK_EMAIL, WEEKLY_SUMMARY_EMAIL or EXPIRY_REMINDER_EMAIL";

    @Mock
    private NotificationConsentRepository notificationConsentRepository;

    @Mock
    private UserService userService;

    @Mock
    private AccountLock accountLock;

    @Mock
    private RateLimiter rateLimiter;

    private final User user = User.builder().id(7L).email("ana@example.com").build();

    private DefaultNotificationConsentService service;

    @BeforeEach
    void setUp() {
        service = new DefaultNotificationConsentService(notificationConsentRepository, userService, accountLock, rateLimiter,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(userService.getUserFromToken()).thenReturn(user);
        lenient().when(accountLock.lock(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rateLimiter.tryAcquire(SETTINGS_KEY, 60, Duration.ofHours(1))).thenReturn(true);
        lenient().when(notificationConsentRepository.save(any(NotificationConsent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void newUsersHaveNoConsents() {
        when(notificationConsentRepository.findByOwnerIdAndChannelIdIsNullAndRevokedAtIsNull(7L)).thenReturn(List.of());

        assertThat(service.mine()).containsExactly(
                new NotificationConsentResponse(NotificationType.DEAD_LINK_EMAIL, false, null,
                        "Email me when one of my links stops working. Links are checked every night."),
                new NotificationConsentResponse(NotificationType.WEEKLY_SUMMARY_EMAIL, false, null,
                        "A summary of last week's clicks every Monday morning."),
                new NotificationConsentResponse(NotificationType.EXPIRY_REMINDER_EMAIL, false, null,
                        "Email me a day before one of my links expires."));
    }

    @Test
    void mineShowsWhenEachEmailWasTurnedOn() {
        when(notificationConsentRepository.findByOwnerIdAndChannelIdIsNullAndRevokedAtIsNull(7L))
                .thenReturn(List.of(active(NotificationType.WEEKLY_SUMMARY_EMAIL)));

        assertThat(service.mine()).extracting(NotificationConsentResponse::type, NotificationConsentResponse::consented, NotificationConsentResponse::grantedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(NotificationType.DEAD_LINK_EMAIL, false, null),
                        org.assertj.core.groups.Tuple.tuple(NotificationType.WEEKLY_SUMMARY_EMAIL, true, GRANTED_AT.minusDays(3)),
                        org.assertj.core.groups.Tuple.tuple(NotificationType.EXPIRY_REMINDER_EMAIL, false, null));
    }

    @Test
    void nobodySignedInIsRefused() {
        when(userService.getUserFromToken()).thenReturn(null);

        assertThatThrownBy(() -> service.mine()).isInstanceOf(NoAuthorizationException.class);
        assertThatThrownBy(() -> service.set("WEEKLY_SUMMARY_EMAIL", true)).isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(accountLock, notificationConsentRepository);
    }

    @Test
    void grantingInsertsARow() {
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.WEEKLY_SUMMARY_EMAIL))
                .thenReturn(Optional.empty());

        final var response = service.set("WEEKLY_SUMMARY_EMAIL", true);

        final var saved = ArgumentCaptor.forClass(NotificationConsent.class);
        verify(notificationConsentRepository).save(saved.capture());
        assertThat(saved.getValue().getOwner()).isSameAs(user);
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.WEEKLY_SUMMARY_EMAIL);
        assertThat(saved.getValue().getChannelId()).isNull();
        assertThat(saved.getValue().getSource()).isEqualTo(ConsentSource.SETTINGS);
        assertThat(saved.getValue().getGrantedAt()).isEqualTo(GRANTED_AT);
        assertThat(saved.getValue().getRevokedAt()).isNull();
        assertThat(response).isEqualTo(new NotificationConsentResponse(NotificationType.WEEKLY_SUMMARY_EMAIL, true, GRANTED_AT,
                "A summary of last week's clicks every Monday morning."));
    }

    @Test
    void grantingTwiceKeepsOneActiveRow() {
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.DEAD_LINK_EMAIL))
                .thenReturn(Optional.of(active(NotificationType.DEAD_LINK_EMAIL)));

        final var response = service.set("DEAD_LINK_EMAIL", true);

        verify(notificationConsentRepository, never()).save(any());
        assertThat(response.consented()).isTrue();
        assertThat(response.grantedAt()).isEqualTo(GRANTED_AT.minusDays(3));
    }

    @Test
    void turningOffWhatIsOffChangesNothing() {
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.DEAD_LINK_EMAIL))
                .thenReturn(Optional.empty());

        final var response = service.set("DEAD_LINK_EMAIL", false);

        verify(notificationConsentRepository, never()).save(any());
        assertThat(response).isEqualTo(new NotificationConsentResponse(NotificationType.DEAD_LINK_EMAIL, false, null,
                "Email me when one of my links stops working. Links are checked every night."));
    }

    @Test
    void revokingSetsRevokedAtAndKeepsTheRow() {
        final var consent = active(NotificationType.EXPIRY_REMINDER_EMAIL);
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.EXPIRY_REMINDER_EMAIL))
                .thenReturn(Optional.of(consent));

        final var response = service.set("EXPIRY_REMINDER_EMAIL", false);

        assertThat(consent.getRevokedAt()).isEqualTo(GRANTED_AT);
        assertThat(consent.getRevokedSource()).isEqualTo(ConsentSource.SETTINGS);
        assertThat(consent.getGrantedAt()).isEqualTo(GRANTED_AT.minusDays(3));
        verify(notificationConsentRepository).save(consent);
        verify(notificationConsentRepository, never()).delete(any());
        assertThat(response.consented()).isFalse();
        assertThat(response.grantedAt()).isNull();
    }

    @Test
    void bothTakeTheUserLock() {
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.DEAD_LINK_EMAIL))
                .thenReturn(Optional.empty(), Optional.of(active(NotificationType.DEAD_LINK_EMAIL)));

        service.set("DEAD_LINK_EMAIL", true);
        service.set("DEAD_LINK_EMAIL", false);

        verify(accountLock, org.mockito.Mockito.times(2)).lock(user);
    }

    @Test
    void channelEventTypesAreRefused() {
        for (final var type : NotificationType.CHANNEL_EVENTS) {
            assertThatThrownBy(() -> service.set(type.name(), true))
                    .isInstanceOf(ApiException.class)
                    .hasMessage(NOT_AN_EMAIL_TYPE);
        }
        verifyNoInteractions(accountLock, rateLimiter, notificationConsentRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LINK_DEAD", "FOO", "dead_link_email", "", " DEAD_LINK_EMAIL"})
    void unknownTypeNamesAreRefused(final String type) {
        assertThatThrownBy(() -> service.set(type, true))
                .isInstanceOf(ApiException.class)
                .hasMessage(NOT_AN_EMAIL_TYPE);
        verifyNoInteractions(accountLock, rateLimiter, notificationConsentRepository);
    }

    @Test
    void overTheLimitIs429() {
        when(rateLimiter.tryAcquire(SETTINGS_KEY, 60, Duration.ofHours(1))).thenReturn(false);

        assertThatThrownBy(() -> service.set("WEEKLY_SUMMARY_EMAIL", true))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("You've changed your notification settings a lot in a short time. Try again in a few minutes.");
        verifyNoInteractions(accountLock, notificationConsentRepository);
    }

    @Test
    void limiterDownAllows() {
        when(rateLimiter.tryAcquire(SETTINGS_KEY, 60, Duration.ofHours(1))).thenThrow(new RateLimiterUnavailableException(new RuntimeException()));
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.WEEKLY_SUMMARY_EMAIL))
                .thenReturn(Optional.empty());

        assertThat(service.set("WEEKLY_SUMMARY_EMAIL", true).consented()).isTrue();
        verify(notificationConsentRepository).save(any(NotificationConsent.class));
    }

    @Test
    void unsubscribeRevokesWithItsSource() {
        final var consent = active(NotificationType.WEEKLY_SUMMARY_EMAIL);
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.WEEKLY_SUMMARY_EMAIL))
                .thenReturn(Optional.of(consent), Optional.of(consent), Optional.empty());

        assertThat(service.revokeByUnsubscribe(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).isTrue();
        assertThat(consent.getRevokedSource()).isEqualTo(ConsentSource.UNSUBSCRIBE_LINK);
        assertThat(consent.getRevokedAt()).isEqualTo(GRANTED_AT);
        assertThat(service.revokeByUnsubscribe(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).isFalse();
        verify(accountLock).lock(org.mockito.ArgumentMatchers.argThat(locked -> locked.getId() == 7L));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void unsubscribeOfAnUnknownUserIsAlreadyOff() {
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(99L, NotificationType.DEAD_LINK_EMAIL))
                .thenReturn(Optional.empty());

        assertThat(service.revokeByUnsubscribe(99L, NotificationType.DEAD_LINK_EMAIL)).isFalse();
        verifyNoInteractions(accountLock);
    }

    @Test
    void hasEmailConsentReadsTheActiveRow() {
        when(notificationConsentRepository.findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(7L, NotificationType.DEAD_LINK_EMAIL))
                .thenReturn(Optional.of(active(NotificationType.DEAD_LINK_EMAIL)));

        assertThat(service.hasEmailConsent(7L, NotificationType.DEAD_LINK_EMAIL)).isTrue();
        assertThat(service.hasEmailConsent(7L, NotificationType.WEEKLY_SUMMARY_EMAIL)).isFalse();
    }

    @Test
    void emailConsentersOfNobodyRunsNoQuery() {
        assertThat(service.emailConsenters(NotificationType.EXPIRY_REMINDER_EMAIL, List.of())).isEmpty();
        verify(notificationConsentRepository, never()).findConsentingUserIds(any(), anyCollection());

        when(notificationConsentRepository.findConsentingUserIds(NotificationType.EXPIRY_REMINDER_EMAIL, List.of(7L, 8L))).thenReturn(List.of(8L));
        assertThat(service.emailConsenters(NotificationType.EXPIRY_REMINDER_EMAIL, List.of(7L, 8L))).containsExactly(8L);
    }

    @Test
    void channelEventsAreGrantedOnceWithTheChannelLabel() {
        final var channel = NotificationChannel.builder().id(4L).name("Team alerts").type(ChannelType.DISCORD).build();
        when(notificationConsentRepository.findByChannelIdAndRevokedAtIsNull(4L))
                .thenReturn(List.of(channelConsent(channel, NotificationType.LINK_DEAD)));

        service.grantChannelEvents(user, channel, EnumSet.of(NotificationType.LINK_DEAD, NotificationType.WEEKLY_SUMMARY));

        final var saved = ArgumentCaptor.forClass(NotificationConsent.class);
        verify(notificationConsentRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.WEEKLY_SUMMARY);
        assertThat(saved.getValue().getChannelId()).isEqualTo(4L);
        assertThat(saved.getValue().getChannelLabel()).isEqualTo("Discord: Team alerts");
        assertThat(saved.getValue().getSource()).isEqualTo(ConsentSource.SETTINGS);
        assertThat(saved.getValue().getGrantedAt()).isEqualTo(GRANTED_AT);
        verify(accountLock).lock(user);
    }

    @Test
    void emailTypesAreNotChannelEvents() {
        final var channel = NotificationChannel.builder().id(4L).name("Team alerts").type(ChannelType.DISCORD).build();
        final var events = Set.of(NotificationType.DEAD_LINK_EMAIL);

        assertThatThrownBy(() -> service.grantChannelEvents(user, channel, events)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notificationConsentRepository);
    }

    @Test
    void channelEventsAreRevokedWithTheirSource() {
        final var channel = NotificationChannel.builder().id(4L).name("Team alerts").type(ChannelType.SLACK).build();
        final var dead = channelConsent(channel, NotificationType.LINK_DEAD);
        final var back = channelConsent(channel, NotificationType.LINK_BACK);
        when(notificationConsentRepository.findByChannelIdAndRevokedAtIsNull(4L)).thenReturn(List.of(dead, back));

        service.revokeChannelEvents(4L, EnumSet.of(NotificationType.LINK_BACK), ConsentSource.CHANNEL_REMOVED);

        assertThat(back.getRevokedAt()).isEqualTo(GRANTED_AT);
        assertThat(back.getRevokedSource()).isEqualTo(ConsentSource.CHANNEL_REMOVED);
        assertThat(dead.getRevokedAt()).isNull();
        assertThat(service.channelEvents(4L)).containsExactlyInAnyOrder(NotificationType.LINK_DEAD, NotificationType.LINK_BACK);
    }

    private NotificationConsent active(final NotificationType type) {
        return NotificationConsent.builder().id(11L).owner(user).type(type).grantedAt(GRANTED_AT.minusDays(3)).source(ConsentSource.SETTINGS).build();
    }

    private NotificationConsent channelConsent(final NotificationChannel channel, final NotificationType type) {
        return NotificationConsent.builder().owner(user).channelId(channel.getId()).type(type).grantedAt(GRANTED_AT.minusDays(1))
                .source(ConsentSource.SETTINGS).build();
    }

}
