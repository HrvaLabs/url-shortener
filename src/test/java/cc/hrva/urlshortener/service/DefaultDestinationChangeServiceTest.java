package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AccountStanding;
import cc.hrva.urlshortener.dto.MissingRequirement;
import cc.hrva.urlshortener.exception.DestinationChangeRefusedException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.LinkDestinationChange;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ChangeChannel;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DestinationChangeOverride;
import cc.hrva.urlshortener.model.enums.RequirementCode;
import cc.hrva.urlshortener.model.enums.ThreatType;
import cc.hrva.urlshortener.repository.LinkDestinationChangeRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultDestinationChangeService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDestinationChangeServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);
    private static final String LIMIT_KEY = "destination-change:40";
    private static final User OWNER = User.builder().id(7L).email("owner@example.com").build();
    private static final MissingRequirement TWO_STEP = MissingRequirement.of(RequirementCode.TWO_STEP_SIGN_IN, "Turn on two-step sign-in");

    @Mock
    private AccountStandingService accountStandingService;

    @Mock
    private SettingsService settingsService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    @Mock
    private LinkDestinationChangeRepository linkDestinationChangeRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @Mock
    private TaskScheduler taskScheduler;

    private DefaultDestinationChangeService destinationChanges;

    @BeforeEach
    void setUp() {
        destinationChanges = new DefaultDestinationChangeService(accountStandingService, userRepository, settingsService, linkReviewRepository, linkDestinationChangeRepository,
                auditLogService, rateLimiter, new UrlCache(cacheManager, taskScheduler, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        lenient().when(settingsService.isOn(SettingKey.DESTINATION_CHANGES)).thenReturn(true);
        lenient().when(accountStandingService.standingOf(OWNER)).thenReturn(new AccountStanding(List.of()));
    }

    @Test
    void anAccountInGoodStandingChangesAHrvaLink() {
        assertThatCode(() -> destinationChanges.requireAllowed(link(null), owner())).doesNotThrowAnyException();
    }

    @Test
    void anAccountNotInGoodStandingIsToldWhatIsMissing() {
        when(accountStandingService.standingOf(OWNER)).thenReturn(new AccountStanding(List.of(TWO_STEP,
                MissingRequirement.of(RequirementCode.VERIFIED_EMAIL, "Verify your email"))));

        assertThatThrownBy(() -> destinationChanges.requireAllowed(link(null), owner()))
                .isInstanceOf(DestinationChangeRefusedException.class)
                .hasMessage("You can't change where this link points yet. Turn on two-step sign-in. Verify your email.")
                .satisfies(refusal -> assertThat(((DestinationChangeRefusedException) refusal).getMissing())
                        .extracting(MissingRequirement::code).containsExactly(RequirementCode.TWO_STEP_SIGN_IN, RequirementCode.VERIFIED_EMAIL));
    }

    @Test
    void aLinkOnTheOwnersActiveDomainNeedsNoGoodStanding() {
        final var url = link(domain(CustomDomainStatus.ACTIVE));

        assertThatCode(() -> destinationChanges.requireAllowed(url, owner())).doesNotThrowAnyException();
        verifyNoInteractions(accountStandingService);
    }

    @Test
    void aLinkOnADomainThatIsNotActiveKeepsItsDestination() {
        assertThat(refusal(link(domain(CustomDomainStatus.DISABLED)))).containsExactly(
                MissingRequirement.of(RequirementCode.DOMAIN_ACTIVE, "This link's domain isn't active"));
    }

    @Test
    void aBlockedOrHeldOrReviewedLinkOnACustomDomainKeepsItsDestination() {
        final var blocked = link(domain(CustomDomainStatus.ACTIVE));
        blocked.setThreatType(ThreatType.MALWARE);
        assertThat(refusal(blocked)).containsExactly(MissingRequirement.of(RequirementCode.LINK_NOT_BLOCKED, "This link is blocked"));

        final var held = link(domain(CustomDomainStatus.ACTIVE));
        held.setBlockReason(BlockReason.SIGN_IN_FORM);
        assertThat(refusal(held)).containsExactly(MissingRequirement.of(RequirementCode.LINK_NOT_BEING_CHECKED, "This link is being checked"));

        final var reviewed = link(domain(CustomDomainStatus.ACTIVE));
        when(linkReviewRepository.existsByUrl_IdAndResolvedAtIsNullAndSystemFlaggedTrue(40L)).thenReturn(true);
        assertThat(refusal(reviewed)).containsExactly(MissingRequirement.of(RequirementCode.LINK_NOT_BEING_CHECKED, "This link is being checked"));
    }

    @Test
    void aLinkMadeWithoutAnAccountNeverChangesForAnOwner() {
        final var url = link(null);
        url.setOwner(null);

        assertThatThrownBy(() -> destinationChanges.requireAllowed(url, owner()))
                .isInstanceOf(DestinationChangeRefusedException.class)
                .hasMessage("Links made without an account can't change where they point.");
    }

    @Test
    void theSiteSwitchStopsEveryOwnerWhateverTheirStanding() {
        when(settingsService.isOn(SettingKey.DESTINATION_CHANGES)).thenReturn(false);

        assertThatThrownBy(() -> destinationChanges.requireAllowed(link(domain(CustomDomainStatus.ACTIVE)), owner()))
                .isInstanceOf(DestinationChangeRefusedException.class)
                .hasMessage("Changing where links point is turned off right now.");
        verifyNoInteractions(accountStandingService);
    }

    @Test
    void anAdminIsNeitherCheckedNorCounted() {
        final var admin = new LinkEditor(OWNER, ChangeChannel.WEB, true);
        final var url = link(null);
        url.setOwner(null);

        destinationChanges.requireAllowed(url, admin);
        destinationChanges.countChange(url, admin);

        verifyNoInteractions(rateLimiter, accountStandingService, settingsService);
        assertThat(destinationChanges.permissionFor(url, admin).allowed()).isTrue();
    }

    @Test
    void theSixthChangeOfTheDayWaitsExactlyUntilTheCounterResets() {
        when(rateLimiter.tryAcquire(LIMIT_KEY, 5, Duration.ofDays(1))).thenReturn(false);
        when(rateLimiter.resetsIn(LIMIT_KEY)).thenReturn(Optional.of(Duration.ofMinutes(42).plusMillis(10)));

        assertThatThrownBy(() -> destinationChanges.countChange(link(null), owner()))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("This link's destination can change 5 times a day. Try again in 43 minutes.")
                .satisfies(refusal -> assertThat(((RateLimitExceededException) refusal).getRetryAfter()).isEqualTo(Duration.ofMinutes(42).plusMillis(10)));
    }

    @Test
    void aLongWaitIsSaidInHoursAndAnUnknownOneIsTheWholeDay() {
        when(rateLimiter.tryAcquire(LIMIT_KEY, 5, Duration.ofDays(1))).thenReturn(false);

        assertThatThrownBy(() -> destinationChanges.countChange(link(null), owner()))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("This link's destination can change 5 times a day. Try again in 24 hours.")
                .satisfies(refusal -> assertThat(((RateLimitExceededException) refusal).getRetryAfter()).isEqualTo(Duration.ofDays(1)));
    }

    @Test
    void changesAreRefusedWhileTheyCannotBeCounted() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenThrow(new RateLimiterUnavailableException(new RuntimeException()));

        assertThatThrownBy(() -> destinationChanges.countChange(link(null), owner()))
                .isInstanceOf(ServiceBusyException.class)
                .hasMessage("Changing where a link points isn't available right now. Try again in a minute.")
                .satisfies(refusal -> assertThat(((ServiceBusyException) refusal).getRetryAfter()).isEqualTo(Duration.ofMinutes(1)));
    }

    @Test
    void aChangeIsKeptInTheHistoryAndTheAuditLogAndEvictedAgainShortly() {
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = link(null);
        url.setLongUrl("https://example.com/new");

        destinationChanges.record(url, "https://example.com/old", LinkEditor.withApiKey(OWNER), false);

        final var saved = ArgumentCaptor.forClass(LinkDestinationChange.class);
        verify(linkDestinationChangeRepository).save(saved.capture());
        assertThat(saved.getValue()).usingRecursiveComparison().isEqualTo(LinkDestinationChange.builder().urlId(40L).oldLongUrl("https://example.com/old")
                .newLongUrl("https://example.com/new").changedBy("owner@example.com").via(ChangeChannel.API_KEY).changedAt(NOW).build());
        verify(auditLogService).log("owner@example.com", AuditAction.CHANGE_LINK_DESTINATION, "Url", "40",
                "from: https://example.com/old, to: https://example.com/new, via: API_KEY");
        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), org.mockito.ArgumentMatchers.eq(NOW.toInstant(ZoneOffset.UTC).plusSeconds(2)));
        verify(cache).evict("abc");
        later.getValue().run();
        verify(cache, times(2)).evict("abc");
    }

    @Test
    void theInfoCountsTheChangesLeftAndSaysWhenTheNextOneIsPossible() {
        when(rateLimiter.count(LIMIT_KEY)).thenReturn(OptionalLong.of(2));
        final var left = destinationChanges.permissionFor(link(null), owner());
        assertThat(left.allowed()).isTrue();
        assertThat(left.changesLeft()).isEqualTo(3);

        when(rateLimiter.count(LIMIT_KEY)).thenReturn(OptionalLong.of(6));
        when(rateLimiter.resetsIn(LIMIT_KEY)).thenReturn(Optional.of(Duration.ofMinutes(90)));
        final var none = destinationChanges.permissionFor(link(null), owner());
        assertThat(none.allowed()).isFalse();
        assertThat(none.changesLeft()).isZero();
        assertThat(none.missing()).containsExactly(new MissingRequirement(RequirementCode.DAILY_CHANGES_LEFT,
                "This link's destination can change 5 times a day. Available from 1 November 2026 at 13:30 UTC", NOW.plusMinutes(90)));
    }

    @Test
    void theInfoListsTheStandingWithoutTheCountWhenTheCounterIsDown() {
        when(accountStandingService.standingOf(OWNER)).thenReturn(new AccountStanding(List.of(TWO_STEP)));

        final var info = destinationChanges.permissionFor(link(null), owner());

        assertThat(info.allowed()).isFalse();
        assertThat(info.missing()).containsExactly(TWO_STEP);
        assertThat(info.changesLeft()).isNull();
    }

    @Test
    void neverAllowedStopsTheAccountOnEveryHostWithItsOwnReason() {
        final var never = User.builder().id(7L).email("owner@example.com").destinationChangeOverride(DestinationChangeOverride.NEVER).build();
        final var editor = LinkEditor.signedIn(never);

        for (final var url : List.of(link(null), link(domain(CustomDomainStatus.ACTIVE)))) {
            assertThatThrownBy(() -> destinationChanges.requireAllowed(url, editor))
                    .isInstanceOf(DestinationChangeRefusedException.class)
                    .hasMessage("An admin turned off destination changes for your account.")
                    .satisfies(refusal -> assertThat(((DestinationChangeRefusedException) refusal).getMissing())
                            .containsExactly(MissingRequirement.of(RequirementCode.ACCOUNT_TURNED_OFF, "An admin turned off destination changes for your account")));
        }
        verifyNoInteractions(accountStandingService);
    }

    @Test
    void alwaysAllowedSkipsGoodStandingButNotTheDomainRulesOrTheSwitch() {
        final var always = User.builder().id(7L).email("owner@example.com").destinationChangeOverride(DestinationChangeOverride.ALWAYS).build();
        final var editor = LinkEditor.signedIn(always);
        final var hrva = link(null);
        hrva.setOwner(always);

        assertThatCode(() -> destinationChanges.requireAllowed(hrva, editor)).doesNotThrowAnyException();
        verifyNoInteractions(accountStandingService);

        final var offDomain = link(domain(CustomDomainStatus.DISABLED));
        assertThatThrownBy(() -> destinationChanges.requireAllowed(offDomain, editor)).isInstanceOf(DestinationChangeRefusedException.class);

        when(settingsService.isOn(SettingKey.DESTINATION_CHANGES)).thenReturn(false);
        assertThatThrownBy(() -> destinationChanges.requireAllowed(hrva, editor))
                .hasMessage("Changing where links point is turned off right now.");
    }

    @Test
    void automaticLeavesItToGoodStanding() {
        final var automatic = User.builder().id(7L).destinationChangeOverride(DestinationChangeOverride.AUTOMATIC).build();
        when(accountStandingService.standingOf(automatic)).thenReturn(new AccountStanding(List.of(TWO_STEP)));

        assertThatThrownBy(() -> destinationChanges.requireAllowed(link(null), LinkEditor.signedIn(automatic)))
                .isInstanceOf(DestinationChangeRefusedException.class)
                .hasMessage("You can't change where this link points yet. Turn on two-step sign-in.");
    }

    @Test
    void anAdminSetsTheOverrideWithItsOldAndNewValueInTheAuditLog() {
        final var user = User.builder().id(7L).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(accountStandingService.standingOf(user)).thenReturn(new AccountStanding(List.of(TWO_STEP)));

        final var standing = destinationChanges.setOverride(7L, DestinationChangeOverride.ALWAYS, "admin@example.com");

        assertThat(user.getDestinationChangeOverride()).isEqualTo(DestinationChangeOverride.ALWAYS);
        verify(userRepository).save(user);
        verify(auditLogService).log("admin@example.com", AuditAction.SET_DESTINATION_CHANGE_OVERRIDE, "User", "7", "from: AUTOMATIC, to: ALWAYS");
        assertThat(standing).isEqualTo(new cc.hrva.urlshortener.dto.DestinationChangeStanding(DestinationChangeOverride.ALWAYS, true, false, List.of(TWO_STEP)));
    }

    @Test
    void settingTheSameOverrideAgainChangesAndRecordsNothing() {
        final var user = User.builder().id(7L).destinationChangeOverride(DestinationChangeOverride.NEVER).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(accountStandingService.standingOf(user)).thenReturn(new AccountStanding(List.of()));

        final var standing = destinationChanges.setOverride(7L, DestinationChangeOverride.NEVER, "admin@example.com");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
        assertThat(standing.allowed()).isFalse();
        assertThat(standing.inGoodStanding()).isTrue();
    }

    @Test
    void anUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> destinationChanges.setOverride(8L, DestinationChangeOverride.NEVER, "admin@example.com"))
                .isInstanceOf(cc.hrva.urlshortener.exception.UserNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void theStandingShowsWhetherTheAccountIsAllowedUnderTheSwitchAndTheOverride() {
        final var user = User.builder().id(7L).build();
        when(accountStandingService.standingsOf(List.of(user))).thenReturn(java.util.Map.of(7L, new AccountStanding(List.of())));

        assertThat(destinationChanges.standingsOf(List.of(user)).get(7L))
                .isEqualTo(new cc.hrva.urlshortener.dto.DestinationChangeStanding(DestinationChangeOverride.AUTOMATIC, true, true, List.of()));

        when(settingsService.isOn(SettingKey.DESTINATION_CHANGES)).thenReturn(false);
        assertThat(destinationChanges.standingsOf(List.of(user)).get(7L).allowed()).isFalse();
    }

    @Test
    void aForcedChangeIsMarkedInTheHistoryAndTheAuditLog() {
        final var url = link(null);
        url.setLongUrl("https://example.com/new");

        destinationChanges.record(url, "https://example.com/old", new LinkEditor(User.builder().id(1L).email("admin@example.com").build(), ChangeChannel.WEB, true), true);

        final var saved = ArgumentCaptor.forClass(LinkDestinationChange.class);
        verify(linkDestinationChangeRepository).save(saved.capture());
        assertThat(saved.getValue().isForced()).isTrue();
        verify(auditLogService).log("admin@example.com", AuditAction.CHANGE_LINK_DESTINATION, "Url", "40",
                "from: https://example.com/old, to: https://example.com/new, via: WEB, forced: true");
    }

    private List<MissingRequirement> refusal(final Url url) {
        try {
            destinationChanges.requireAllowed(url, owner());
        } catch (final DestinationChangeRefusedException refusal) {
            return refusal.getMissing();
        }
        throw new AssertionError("The change was allowed");
    }

    private static LinkEditor owner() {
        return LinkEditor.signedIn(OWNER);
    }

    private static Url link(final CustomDomain domain) {
        return Url.builder().id(40L).shortUrl("abc").longUrl("https://example.com/old").owner(OWNER).domain(domain).active(true).build();
    }

    private static CustomDomain domain(final CustomDomainStatus status) {
        return CustomDomain.builder().id(3L).owner(OWNER).hostname("go.example.com").status(status).build();
    }

}
