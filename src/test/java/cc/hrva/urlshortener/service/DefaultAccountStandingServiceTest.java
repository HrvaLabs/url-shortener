package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.MissingRequirement;
import cc.hrva.urlshortener.dto.SignInMethodResponse;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.RequirementCode;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultAccountStandingService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccountStandingServiceTest {

    private static final long OWNER = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    @Mock
    private SignInMethodService signInMethodService;

    private DefaultAccountStandingService standingService;

    @BeforeEach
    void setUp() {
        standingService = new DefaultAccountStandingService(urlRepository, linkReviewRepository, signInMethodService,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        lenient().when(signInMethodService.methodsOf(OWNER)).thenReturn(List.of());
    }

    @Test
    void anAccountWithEveryRequirementIsInGoodStanding() {
        final var standing = standingService.standingOf(inGoodStanding().build());

        assertThat(standing.good()).isTrue();
        assertThat(standing.missing()).isEmpty();
    }

    @Test
    void twoStepSignInMustBeOn() {
        assertThat(standingService.standingOf(inGoodStanding().twoStepEnabled(false).build()).missing())
                .containsExactly(MissingRequirement.of(RequirementCode.TWO_STEP_SIGN_IN, "Turn on two-step sign-in"));
        assertThat(standingService.standingOf(inGoodStanding().twoStepEnabled(null).build()).missing())
                .extracting(MissingRequirement::code).containsExactly(RequirementCode.TWO_STEP_SIGN_IN);
    }

    @Test
    void anAccountYoungerThanThirtyDaysIsToldTheDayItCanChangeDestinations() {
        final var created = NOW.minusDays(29);

        assertThat(standingService.standingOf(inGoodStanding().createDate(created).build()).missing())
                .containsExactly(new MissingRequirement(RequirementCode.ACCOUNT_AGE, "Available from 2 November 2026", created.plusDays(30)));
        assertThat(standingService.standingOf(inGoodStanding().createDate(NOW.minusDays(30)).build()).good()).isTrue();
    }

    @Test
    void anUnverifiedEmailCountsUnlessTheAccountSignsInWithGoogleOrGithub() {
        assertThat(standingService.standingOf(inGoodStanding().emailVerified(false).build()).missing())
                .containsExactly(MissingRequirement.of(RequirementCode.VERIFIED_EMAIL, "Verify your email"));

        when(signInMethodService.methodsOf(OWNER)).thenReturn(List.of(new SignInMethodResponse(SignInMethod.GITHUB, NOW, NOW, null)));
        assertThat(standingService.standingOf(inGoodStanding().emailVerified(false).build()).good()).isTrue();

        when(signInMethodService.methodsOf(OWNER)).thenReturn(List.of(new SignInMethodResponse(SignInMethod.PASSKEY, NOW, NOW, null),
                new SignInMethodResponse(SignInMethod.GOOGLE, NOW, NOW, NOW)));
        assertThat(standingService.standingOf(inGoodStanding().emailVerified(false).build()).missing())
                .extracting(MissingRequirement::code).containsExactly(RequirementCode.VERIFIED_EMAIL);
    }

    @Test
    void aBlockedLinkCounts() {
        when(urlRepository.ownerHasBlockedLink(OWNER, BlockReason.HOLDS)).thenReturn(true);

        assertThat(standingService.standingOf(inGoodStanding().build()).missing())
                .containsExactly(MissingRequirement.of(RequirementCode.NO_BLOCKED_LINKS, "One of your links is blocked"));
    }

    @Test
    void aHeldLinkCountsAsBeingChecked() {
        when(urlRepository.ownerHasHeldLink(OWNER, BlockReason.HOLDS)).thenReturn(true);

        assertThat(standingService.standingOf(inGoodStanding().build()).missing())
                .containsExactly(MissingRequirement.of(RequirementCode.NO_LINKS_BEING_CHECKED, "One of your links is being checked"));
    }

    @Test
    void aLinkInTheReviewQueueCountsAsBeingChecked() {
        when(linkReviewRepository.existsByUrl_Owner_IdAndResolvedAtIsNull(OWNER)).thenReturn(true);

        assertThat(standingService.standingOf(inGoodStanding().build()).missing())
                .containsExactly(MissingRequirement.of(RequirementCode.NO_LINKS_BEING_CHECKED, "One of your links is being checked"));
    }

    @Test
    void aBlockInTheLastNinetyDaysCountsUntilNinetyDaysHavePassed() {
        final var blocked = NOW.minusDays(89);

        assertThat(standingService.standingOf(inGoodStanding().lastLinkBlockedAt(blocked).build()).missing())
                .containsExactly(new MissingRequirement(RequirementCode.NO_RECENT_BLOCKS, "One of your links was blocked. Available from 2 November 2026",
                        blocked.plusDays(90)));
        assertThat(standingService.standingOf(inGoodStanding().lastLinkBlockedAt(NOW.minusDays(90)).build()).good()).isTrue();
    }

    @Test
    void aLinkThatIsStillBlockedIsListedOnceNotAlsoAsARecentBlock() {
        when(urlRepository.ownerHasBlockedLink(OWNER, BlockReason.HOLDS)).thenReturn(true);

        assertThat(standingService.standingOf(inGoodStanding().lastLinkBlockedAt(NOW.minusDays(3)).build()).missing())
                .extracting(MissingRequirement::code).containsExactly(RequirementCode.NO_BLOCKED_LINKS);
    }

    @Test
    void everyMissingRequirementIsListedInOrder() {
        when(urlRepository.ownerHasBlockedLink(OWNER, BlockReason.HOLDS)).thenReturn(true);
        when(linkReviewRepository.existsByUrl_Owner_IdAndResolvedAtIsNull(OWNER)).thenReturn(true);

        assertThat(standingService.standingOf(User.builder().id(OWNER).createDate(NOW.minusDays(1)).build()).missing())
                .extracting(MissingRequirement::code)
                .containsExactly(RequirementCode.TWO_STEP_SIGN_IN, RequirementCode.ACCOUNT_AGE, RequirementCode.VERIFIED_EMAIL,
                        RequirementCode.NO_BLOCKED_LINKS, RequirementCode.NO_LINKS_BEING_CHECKED);
    }

    private static User.UserBuilder inGoodStanding() {
        return User.builder()
                .id(OWNER)
                .twoStepEnabled(true)
                .emailVerified(true)
                .createDate(NOW.minusDays(400));
    }

}
