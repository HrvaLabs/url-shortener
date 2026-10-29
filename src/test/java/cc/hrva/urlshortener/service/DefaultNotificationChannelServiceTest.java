package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.Resolver;
import cc.hrva.urlshortener.dto.notification.LastDelivery;
import cc.hrva.urlshortener.dto.notification.NotificationChannelRequest;
import cc.hrva.urlshortener.dto.notification.NotificationChannelResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ChannelValidationException;
import cc.hrva.urlshortener.exception.NotificationChannelNotFoundException;
import cc.hrva.urlshortener.exception.NotificationTestUnavailableException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.NotificationChannelRepository;
import cc.hrva.urlshortener.repository.NotificationConsentRepository;
import cc.hrva.urlshortener.service.impl.DefaultNotificationChannelService;
import cc.hrva.urlshortener.service.notification.ChannelTargets;
import cc.hrva.urlshortener.service.notification.NotificationTestKeys;
import cc.hrva.urlshortener.service.notification.SecretBox;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultNotificationChannelServiceTest {

    private static final String BULLETS = Character.toString(0x2022).repeat(4);
    private static final String DISCORD_URL = "https://discord.com/api/webhooks/123456/abcDEF-secret";
    private static final String WEBHOOK_URL = "https://hooks.example.org/hrva/s3cr3t-path";
    private static final String NTFY_URL = "https://ntfy.sh/my-secret-topic";
    private static final String NTFY_TOKEN = "tk_ntfyaccesstoken0001";
    private static final String BOT_TOKEN = "123456789:AAH4Fq2xYzAbCdEfGhIjKlMnOpQrStUvWxYz";
    private static final String SETTINGS_KEY = "notify-settings:user:7";
    private static final String TEST_KEY = "notify-test:user:7";
    private static final String EVENTS_TEXT = "Events must be LINK_DEAD, LINK_BACK, DAILY_SUMMARY or WEEKLY_SUMMARY";
    private static final long ANA = 7L;

    @Mock
    private NotificationChannelRepository notificationChannelRepository;

    @Mock
    private NotificationConsentRepository notificationConsentRepository;

    @Mock
    private NotificationConsentService notificationConsentService;

    @Mock
    private UserService userService;

    @Mock
    private AccountLock accountLock;

    @Mock
    private RateLimiter rateLimiter;

    private final User user = User.builder().id(ANA).email("ana@example.com").build();
    private final SecretBox secretBox = new SecretBox(NotificationTestKeys.keys());
    private final Resolver publicDns = host -> new InetAddress[] {InetAddress.ofLiteral("162.159.128.233")};
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private DefaultNotificationChannelService service;

    @BeforeEach
    void setUp() {
        service = new DefaultNotificationChannelService(notificationChannelRepository, notificationConsentRepository, notificationConsentService,
                userService, accountLock, rateLimiter, secretBox, new ChannelTargets(publicDns));
        lenient().when(userService.getUserFromToken()).thenReturn(user);
        lenient().when(accountLock.lock(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(rateLimiter.tryAcquire(SETTINGS_KEY, 60, Duration.ofHours(1))).thenReturn(true);
        lenient().when(rateLimiter.tryAcquire(TEST_KEY, 10, Duration.ofMinutes(10))).thenReturn(true);
        lenient().when(notificationChannelRepository.save(any(NotificationChannel.class))).thenAnswer(invocation -> {
            final NotificationChannel channel = invocation.getArgument(0);
            if (channel.getId() == null) {
                channel.setId(4L);
            }
            return channel;
        });
    }

    @Test
    void createSealsSecretsAndGrantsEvents() {
        final var response = service.create(discord().build());

        final var saved = savedChannel();
        assertThat(saved.getTargetSealed()).startsWith("v1:").doesNotContain(DISCORD_URL, "abcDEF");
        assertThat(secretBox.open(saved.getTargetSealed(), SecretBox.contextFor(ANA))).isEqualTo(DISCORD_URL);
        assertThat(saved.getOwner().getId()).isEqualTo(ANA);
        assertThat(saved.getName()).isEqualTo("Team alerts");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getMaskedTarget()).isEqualTo("https://discord.com/" + BULLETS + "cret");
        assertThat(saved.getSigningSecretSealed()).isNull();
        assertThat(saved.getAccessTokenSealed()).isNull();
        verify(notificationConsentService).grantChannelEvents(user, saved, EnumSet.of(NotificationType.LINK_DEAD));
        assertThat(response.id()).isEqualTo(4L);
        assertThat(response.events()).containsExactly(NotificationType.LINK_DEAD);
        assertThat(response.maskedTarget()).isEqualTo("https://discord.com/" + BULLETS + "cret");
        assertThat(response.signingSecret()).isNull();
        assertThat(response.lastDelivery()).isNull();
    }

    @Test
    void theNameIsTrimmedAndEnabledDefaultsToOn() {
        service.create(discord().name("  Team alerts  ").enabled(null).target("  " + DISCORD_URL + " ").build());

        final var saved = savedChannel();
        assertThat(saved.getName()).isEqualTo("Team alerts");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(secretBox.open(saved.getTargetSealed(), SecretBox.contextFor(ANA))).isEqualTo(DISCORD_URL);
    }

    @Test
    void aChannelCanStartSwitchedOff() {
        service.create(discord().enabled(false).build());

        assertThat(savedChannel().isEnabled()).isFalse();
    }

    @Test
    void createTakesTheAccountLockBeforeCounting() {
        service.create(discord().build());

        final InOrder order = inOrder(accountLock, notificationChannelRepository, notificationConsentService);
        order.verify(accountLock).lock(user);
        order.verify(notificationChannelRepository).countByOwnerId(ANA);
        order.verify(notificationChannelRepository).save(any(NotificationChannel.class));
        order.verify(notificationConsentService).grantChannelEvents(any(), any(), any());
    }

    @Test
    void webhookCreateReturnsTheSigningSecretOnce() {
        final var response = service.create(request(ChannelType.WEBHOOK).target(WEBHOOK_URL).build());

        final var saved = savedChannel();
        assertThat(response.signingSecret()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(Base64.getUrlDecoder().decode(response.signingSecret())).hasSize(32);
        assertThat(saved.getSigningSecretSealed()).startsWith("v1:").doesNotContain(response.signingSecret());
        assertThat(secretBox.open(saved.getSigningSecretSealed(), SecretBox.contextFor(ANA))).isEqualTo(response.signingSecret());

        when(notificationChannelRepository.findByOwnerIdOrderByCreatedAtAscIdAsc(ANA)).thenReturn(List.of(saved));
        assertThat(service.mine()).singleElement().extracting(NotificationChannelResponse::signingSecret).isNull();
    }

    @Test
    void twoWebhooksGetDifferentSecrets() {
        final var first = service.create(request(ChannelType.WEBHOOK).target(WEBHOOK_URL).build());
        final var second = service.create(request(ChannelType.WEBHOOK).target(WEBHOOK_URL).build());

        assertThat(first.signingSecret()).isNotEqualTo(second.signingSecret());
    }

    @Test
    void responsesCarryOnlyMasks() {
        final var webhook = service.create(request(ChannelType.WEBHOOK).target(WEBHOOK_URL).build());
        final var ntfy = service.create(request(ChannelType.NTFY).target(NTFY_URL).accessToken(NTFY_TOKEN).build());
        final var telegram = service.create(request(ChannelType.TELEGRAM).botToken(BOT_TOKEN).chatId("-1001234567890").build());
        final var discord = service.create(discord().build());
        final var saved = savedChannels();
        when(notificationChannelRepository.findByOwnerIdOrderByCreatedAtAscIdAsc(ANA)).thenReturn(saved);

        final var json = jsonMapper.writeValueAsString(List.of(webhook, ntfy, telegram, discord, service.mine()));

        assertThat(json).doesNotContain(WEBHOOK_URL, "s3cr3t", NTFY_URL, "my-secret-topic", NTFY_TOKEN, BOT_TOKEN, "AAH4Fq2xYz", DISCORD_URL, "abcDEF");
        assertThat(json).contains("https://ntfy.sh/" + BULLETS + "opic", "Bot " + BULLETS + "WxYz", "-1001234567890");
        assertThat(ntfy.hasAccessToken()).isTrue();
        assertThat(discord.hasAccessToken()).isFalse();
        assertThat(telegram.chatId()).isEqualTo("-1001234567890");
        assertThat(json).doesNotContain("\"signingSecret\":null");
    }

    @Test
    void telegramKeepsTheTokenSealedAndTheChatIdPlain() {
        service.create(request(ChannelType.TELEGRAM).botToken(" " + BOT_TOKEN + " ").chatId(" @mychannel ").target("ignored").build());

        final var saved = savedChannel();
        assertThat(secretBox.open(saved.getTargetSealed(), SecretBox.contextFor(ANA))).isEqualTo(BOT_TOKEN);
        assertThat(saved.getChatId()).isEqualTo("@mychannel");
        assertThat(saved.getMaskedTarget()).isEqualTo("Bot " + BULLETS + "WxYz");
    }

    @Test
    void anAccessTokenIsKeptOnlyForNtfy() {
        service.create(request(ChannelType.NTFY).target(NTFY_URL).accessToken(NTFY_TOKEN).build());
        service.create(discord().accessToken(NTFY_TOKEN).build());

        final var saved = savedChannels();
        assertThat(secretBox.open(saved.get(0).getAccessTokenSealed(), SecretBox.contextFor(ANA))).isEqualTo(NTFY_TOKEN);
        assertThat(saved.get(1).getAccessTokenSealed()).isNull();
    }

    @Test
    void anInvalidTargetIsAFieldErrorAndNothingIsSaved() {
        assertThatThrownBy(() -> service.create(discord().target("http://discord.com/api/webhooks/1/a").build()))
                .isInstanceOfSatisfying(ChannelValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).containsExactly(java.util.Map.entry("target", "Use an https URL.")));

        verify(notificationChannelRepository, never()).save(any());
        verify(notificationConsentService, never()).grantChannelEvents(any(), any(), any());
    }

    @Test
    void eleventhChannelIsRefused() {
        when(notificationChannelRepository.countByOwnerId(ANA)).thenReturn(10L);

        assertThatThrownBy(() -> service.create(discord().build()))
                .isInstanceOf(ApiException.class)
                .hasMessage("You can have up to 10 channels. Remove one to add another.");
        verify(notificationChannelRepository, never()).save(any());
    }

    @Test
    void theTenthChannelIsStillAllowed() {
        when(notificationChannelRepository.countByOwnerId(ANA)).thenReturn(9L);

        service.create(discord().build());

        verify(notificationChannelRepository).save(any());
    }

    @Test
    void unknownOrEmailEventsAreRefused() {
        for (final var events : List.of(Set.of("FOO"), Set.of("DEAD_LINK_EMAIL"), Set.of("LINK_DEAD", "link_back"))) {
            assertThatThrownBy(() -> service.create(discord().events(events).build()))
                    .isInstanceOf(ApiException.class)
                    .hasMessage(EVENTS_TEXT);
            assertThatThrownBy(() -> service.update(4L, discord().events(events).build()))
                    .isInstanceOf(ApiException.class)
                    .hasMessage(EVENTS_TEXT);
        }
        verify(notificationChannelRepository, never()).save(any());
    }

    @Test
    void noEventsIsAllowed() {
        final var response = service.create(discord().events(null).build());

        final var saved = savedChannel();
        assertThat(response.events()).isEmpty();
        verify(notificationConsentService).grantChannelEvents(user, saved, EnumSet.noneOf(NotificationType.class));
    }

    @Test
    void eventsComeBackInTheirOwnOrder() {
        final var response = service.create(discord().events(Set.of("WEEKLY_SUMMARY", "LINK_DEAD", "DAILY_SUMMARY", "LINK_BACK")).build());

        assertThat(response.events()).containsExactly(NotificationType.LINK_DEAD, NotificationType.LINK_BACK, NotificationType.DAILY_SUMMARY,
                NotificationType.WEEKLY_SUMMARY);
    }

    @Test
    void settingsChangesOverTheLimitAre429() {
        when(rateLimiter.tryAcquire(SETTINGS_KEY, 60, Duration.ofHours(1))).thenReturn(false);
        final var message = "You've changed your notification settings a lot in a short time. Try again in a few minutes.";

        assertThatThrownBy(() -> service.create(discord().build())).isInstanceOf(RateLimitExceededException.class).hasMessage(message);
        assertThatThrownBy(() -> service.update(4L, discord().build())).isInstanceOf(RateLimitExceededException.class).hasMessage(message);
        assertThatThrownBy(() -> service.delete(4L)).isInstanceOf(RateLimitExceededException.class).hasMessage(message);
        verify(notificationChannelRepository, never()).save(any());
        verify(notificationChannelRepository, never()).delete(any());
    }

    @Test
    void aSettingsLimiterThatIsDownAllowsTheChange() {
        when(rateLimiter.tryAcquire(SETTINGS_KEY, 60, Duration.ofHours(1))).thenThrow(new RateLimiterUnavailableException(new IllegalStateException("redis down")));

        service.create(discord().build());

        verify(notificationChannelRepository).save(any());
    }

    @Test
    void typeCannotChange() {
        owned(channel(ChannelType.DISCORD));

        assertThatThrownBy(() -> service.update(4L, request(ChannelType.SLACK).build()))
                .isInstanceOf(ApiException.class)
                .hasMessage("A channel's type can't be changed. Add a new channel instead.");
        verify(notificationChannelRepository, never()).save(any());
    }

    @Test
    void updateDiffsEvents() {
        final var channel = owned(channel(ChannelType.DISCORD));
        when(notificationConsentService.channelEvents(4L)).thenReturn(EnumSet.of(NotificationType.LINK_DEAD, NotificationType.DAILY_SUMMARY));

        final var response = service.update(4L, discord().target(null).events(Set.of("DAILY_SUMMARY", "WEEKLY_SUMMARY")).build());

        verify(notificationConsentService).grantChannelEvents(channel.getOwner(), channel, EnumSet.of(NotificationType.WEEKLY_SUMMARY));
        verify(notificationConsentService).revokeChannelEvents(4L, EnumSet.of(NotificationType.LINK_DEAD), ConsentSource.SETTINGS);
        assertThat(response.events()).containsExactly(NotificationType.DAILY_SUMMARY, NotificationType.WEEKLY_SUMMARY);
    }

    @Test
    void updateKeepsSecretsWhenBlank() {
        final var channel = owned(channel(ChannelType.NTFY));
        final var target = channel.getTargetSealed();
        final var token = channel.getAccessTokenSealed();

        final var response = service.update(4L, request(ChannelType.NTFY).name("Renamed").target(" ").accessToken(null).build());

        assertThat(channel.getTargetSealed()).isEqualTo(target);
        assertThat(channel.getAccessTokenSealed()).isEqualTo(token);
        assertThat(channel.getMaskedTarget()).isEqualTo("https://ntfy.sh/" + BULLETS + "opic");
        assertThat(channel.getName()).isEqualTo("Renamed");
        assertThat(response.hasAccessToken()).isTrue();
    }

    @Test
    void updateReplacesSecretsThatAreGiven() {
        final var channel = owned(channel(ChannelType.NTFY));

        service.update(4L, request(ChannelType.NTFY).target("https://ntfy.sh/another-topic").accessToken("tk_new").build());

        assertThat(secretBox.open(channel.getTargetSealed(), SecretBox.contextFor(ANA))).isEqualTo("https://ntfy.sh/another-topic");
        assertThat(secretBox.open(channel.getAccessTokenSealed(), SecretBox.contextFor(ANA))).isEqualTo("tk_new");
        assertThat(channel.getMaskedTarget()).isEqualTo("https://ntfy.sh/" + BULLETS + "opic");
    }

    @Test
    void updateOfATelegramChannelKeepsTheTokenAndChatIdWhenBlank() {
        final var channel = owned(channel(ChannelType.TELEGRAM));
        final var token = channel.getTargetSealed();

        service.update(4L, request(ChannelType.TELEGRAM).chatId("").build());
        assertThat(channel.getTargetSealed()).isEqualTo(token);
        assertThat(channel.getChatId()).isEqualTo("-1001234567890");

        service.update(4L, request(ChannelType.TELEGRAM).chatId("@mychannel").build());
        assertThat(channel.getChatId()).isEqualTo("@mychannel");
    }

    @Test
    void updateChecksANewTarget() {
        owned(channel(ChannelType.DISCORD));

        assertThatThrownBy(() -> service.update(4L, discord().target("https://hooks.slack.com/services/T0/B0/x").build()))
                .isInstanceOf(ChannelValidationException.class);
        verify(notificationChannelRepository, never()).save(any());
    }

    @Test
    void reEnablingClearsAutoDisable() {
        final var channel = channel(ChannelType.DISCORD);
        channel.setEnabled(false);
        channel.setAutoDisabledAt(LocalDateTime.of(2026, 10, 25, 2, 0));
        channel.setFailedDays(10);
        channel.setLastFailedDay(LocalDate.of(2026, 10, 25));
        owned(channel);

        final var response = service.update(4L, discord().target(null).enabled(true).build());

        assertThat(channel.isEnabled()).isTrue();
        assertThat(channel.getAutoDisabledAt()).isNull();
        assertThat(channel.getFailedDays()).isZero();
        assertThat(channel.getLastFailedDay()).isNull();
        assertThat(response.autoDisabledAt()).isNull();
        assertThat(response.enabled()).isTrue();
    }

    @Test
    void enabledLeftOutKeepsTheSwitch() {
        final var channel = channel(ChannelType.DISCORD);
        channel.setEnabled(false);
        channel.setAutoDisabledAt(LocalDateTime.of(2026, 10, 25, 2, 0));
        owned(channel);

        service.update(4L, discord().target(null).enabled(null).build());

        assertThat(channel.isEnabled()).isFalse();
        assertThat(channel.getAutoDisabledAt()).isNotNull();
    }

    @Test
    void switchingOffKeepsTheFailedDays() {
        final var channel = channel(ChannelType.DISCORD);
        channel.setFailedDays(3);
        owned(channel);

        service.update(4L, discord().target(null).enabled(false).build());

        assertThat(channel.isEnabled()).isFalse();
        assertThat(channel.getFailedDays()).isEqualTo(3);
    }

    @Test
    void deleteRevokesThenDeletes() {
        final var channel = owned(channel(ChannelType.DISCORD));

        service.delete(4L);

        final InOrder order = inOrder(notificationConsentService, notificationChannelRepository);
        order.verify(notificationConsentService).revokeChannelEvents(4L, EnumSet.copyOf(NotificationType.CHANNEL_EVENTS), ConsentSource.CHANNEL_REMOVED);
        order.verify(notificationChannelRepository).delete(channel);
    }

    @Test
    void someoneElsesChannelIs404() {
        when(notificationChannelRepository.findByIdAndOwnerId(4L, ANA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(4L, discord().build()))
                .isInstanceOf(NotificationChannelNotFoundException.class).hasMessage("Notification channel not found");
        assertThatThrownBy(() -> service.delete(4L)).isInstanceOf(NotificationChannelNotFoundException.class);
        assertThatThrownBy(() -> service.newSigningSecret(4L)).isInstanceOf(NotificationChannelNotFoundException.class);
        assertThatThrownBy(() -> service.loadOwned(4L)).isInstanceOf(NotificationChannelNotFoundException.class);
        verify(notificationChannelRepository, never()).findById(anyLong());
        verify(notificationChannelRepository, never()).delete(any());
        verify(notificationConsentService, never()).revokeChannelEvents(anyLong(), any(), any());
    }

    @Test
    void signingSecretOnlyForWebhooks() {
        owned(channel(ChannelType.DISCORD));

        assertThatThrownBy(() -> service.newSigningSecret(4L))
                .isInstanceOf(ApiException.class)
                .hasMessage("Only webhook channels have a signing secret.");
        verify(notificationChannelRepository, never()).save(any());
    }

    @Test
    void aNewSigningSecretReplacesTheOldOne() {
        final var channel = owned(channel(ChannelType.WEBHOOK));
        final var old = secretBox.open(channel.getSigningSecretSealed(), SecretBox.contextFor(ANA));

        final var response = service.newSigningSecret(4L);

        assertThat(response.signingSecret()).isNotEqualTo(old).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(secretBox.open(channel.getSigningSecretSealed(), SecretBox.contextFor(ANA))).isEqualTo(response.signingSecret());
    }

    @Test
    void newSigningSecretsOverTheTestLimitAre429() {
        owned(channel(ChannelType.WEBHOOK));
        when(rateLimiter.tryAcquire(TEST_KEY, 10, Duration.ofMinutes(10))).thenReturn(false);

        assertThatThrownBy(() -> service.newSigningSecret(4L))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("You've sent a lot of tests in a short time. Try again in a few minutes.");
        verify(notificationChannelRepository, never()).save(any());
    }

    @Test
    void newSigningSecretsFailClosedWhenTheLimiterIsDown() {
        owned(channel(ChannelType.WEBHOOK));
        when(rateLimiter.tryAcquire(TEST_KEY, 10, Duration.ofMinutes(10))).thenThrow(new RateLimiterUnavailableException(new IllegalStateException("redis down")));

        assertThatThrownBy(() -> service.newSigningSecret(4L))
                .isInstanceOf(NotificationTestUnavailableException.class)
                .hasMessage("Sending a test isn't available right now. Try again in a minute.");
        verify(notificationChannelRepository, never()).save(any());
    }

    @Test
    void mineReadsEveryChannelsEventsInOneQuery() {
        final var discord = channel(ChannelType.DISCORD);
        final var webhook = channel(ChannelType.WEBHOOK);
        webhook.setId(5L);
        webhook.setLastDeliveryAt(LocalDateTime.of(2026, 10, 27, 2, 14, 9));
        webhook.setLastDeliveryStatus("SENT");
        webhook.setLastDeliveryHttp(204);
        when(notificationChannelRepository.findByOwnerIdOrderByCreatedAtAscIdAsc(ANA)).thenReturn(List.of(discord, webhook));
        when(notificationConsentRepository.findByChannelIdInAndRevokedAtIsNull(List.of(4L, 5L))).thenReturn(List.of(
                consent(5L, NotificationType.WEEKLY_SUMMARY), consent(4L, NotificationType.LINK_BACK), consent(5L, NotificationType.LINK_DEAD)));

        final var mine = service.mine();

        assertThat(mine).extracting(NotificationChannelResponse::id).containsExactly(4L, 5L);
        assertThat(mine.get(0).events()).containsExactly(NotificationType.LINK_BACK);
        assertThat(mine.get(1).events()).containsExactly(NotificationType.LINK_DEAD, NotificationType.WEEKLY_SUMMARY);
        assertThat(mine.get(0).lastDelivery()).isNull();
        assertThat(mine.get(1).lastDelivery()).isEqualTo(new LastDelivery("SENT", LocalDateTime.of(2026, 10, 27, 2, 14, 9), 204, null));
        verify(notificationConsentRepository).findByChannelIdInAndRevokedAtIsNull(anyCollection());
    }

    @Test
    void mineWithoutChannelsRunsNoConsentQuery() {
        when(notificationChannelRepository.findByOwnerIdOrderByCreatedAtAscIdAsc(ANA)).thenReturn(List.of());

        assertThat(service.mine()).isEmpty();
        verify(notificationConsentRepository, never()).findByChannelIdInAndRevokedAtIsNull(anyCollection());
    }

    @Test
    void openTargetOpensEverySecret() {
        final var webhook = channel(ChannelType.WEBHOOK);
        final var ntfy = channel(ChannelType.NTFY);
        final var telegram = channel(ChannelType.TELEGRAM);

        assertThat(service.openTarget(webhook).target()).isEqualTo(WEBHOOK_URL);
        assertThat(service.openTarget(webhook).signingSecret()).isEqualTo("old-signing-secret");
        assertThat(service.openTarget(ntfy).accessToken()).isEqualTo(NTFY_TOKEN);
        assertThat(service.openTarget(telegram).target()).isEqualTo(BOT_TOKEN);
        assertThat(service.openTarget(telegram).chatId()).isEqualTo("-1001234567890");
        assertThat(service.openTarget(webhook).toString()).doesNotContain(WEBHOOK_URL, "old-signing-secret");
    }

    @Test
    void loadOwnedReturnsTheCallersChannel() {
        final var channel = owned(channel(ChannelType.DISCORD));

        assertThat(service.loadOwned(4L)).isSameAs(channel);
    }

    @Test
    void theRequestNeverPrintsItsSecrets() {
        final var request = request(ChannelType.NTFY).target(NTFY_URL).accessToken(NTFY_TOKEN).botToken(BOT_TOKEN).chatId("-100999").build();

        assertThat(request.toString()).doesNotContain(NTFY_URL, NTFY_TOKEN, BOT_TOKEN, "-100999").contains("Team alerts");
    }

    private NotificationChannel owned(final NotificationChannel channel) {
        lenient().when(notificationChannelRepository.findByIdAndOwnerId(channel.getId(), ANA)).thenReturn(Optional.of(channel));
        return channel;
    }

    private NotificationChannel channel(final ChannelType type) {
        final var context = SecretBox.contextFor(ANA);
        final var builder = NotificationChannel.builder()
                .id(4L)
                .owner(User.builder().id(ANA).build())
                .name("Team alerts")
                .type(type)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 10, 20, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 10, 20, 9, 0));
        return switch (type) {
            case DISCORD -> builder.targetSealed(secretBox.seal(DISCORD_URL, context)).maskedTarget(ChannelTargets.mask(type, DISCORD_URL)).build();
            case SLACK -> builder.targetSealed(secretBox.seal("https://hooks.slack.com/services/T0/B0/xyz", context)).maskedTarget("m").build();
            case NTFY -> builder.targetSealed(secretBox.seal(NTFY_URL, context)).accessTokenSealed(secretBox.seal(NTFY_TOKEN, context))
                    .maskedTarget(ChannelTargets.mask(type, NTFY_URL)).build();
            case TELEGRAM -> builder.targetSealed(secretBox.seal(BOT_TOKEN, context)).chatId("-1001234567890")
                    .maskedTarget(ChannelTargets.mask(type, BOT_TOKEN)).build();
            case WEBHOOK -> builder.targetSealed(secretBox.seal(WEBHOOK_URL, context)).signingSecretSealed(secretBox.seal("old-signing-secret", context))
                    .maskedTarget(ChannelTargets.mask(type, WEBHOOK_URL)).build();
        };
    }

    private static NotificationConsent consent(final long channelId, final NotificationType type) {
        return NotificationConsent.builder().channelId(channelId).type(type).owner(User.builder().id(ANA).build())
                .grantedAt(LocalDateTime.of(2026, 10, 20, 9, 0)).source(ConsentSource.SETTINGS).build();
    }

    private NotificationChannel savedChannel() {
        final var captor = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(notificationChannelRepository).save(captor.capture());
        return captor.getValue();
    }

    private List<NotificationChannel> savedChannels() {
        final var captor = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(notificationChannelRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static NotificationChannelRequest.NotificationChannelRequestBuilder discord() {
        return request(ChannelType.DISCORD).target(DISCORD_URL);
    }

    private static NotificationChannelRequest.NotificationChannelRequestBuilder request(final ChannelType type) {
        return NotificationChannelRequest.builder().name("Team alerts").type(type).enabled(true).events(Set.of("LINK_DEAD"));
    }

}
