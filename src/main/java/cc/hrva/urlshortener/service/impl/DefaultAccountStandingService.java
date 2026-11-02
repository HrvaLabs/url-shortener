package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AccountStanding;
import cc.hrva.urlshortener.dto.MissingRequirement;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.RequirementCode;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.AccountStandingService;
import cc.hrva.urlshortener.service.SignInMethodService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultAccountStandingService implements AccountStandingService {

    public static final Duration MINIMUM_ACCOUNT_AGE = Duration.ofDays(30);
    public static final Duration BLOCK_COOLDOWN = Duration.ofDays(90);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Set<SignInMethod> PROVIDERS = Set.of(SignInMethod.GOOGLE, SignInMethod.GITHUB);
    private static final String TURN_ON_TWO_STEP = "Turn on two-step sign-in";
    private static final String AVAILABLE_FROM = "Available from %s";
    private static final String VERIFY_EMAIL = "Verify your email";
    private static final String LINK_BLOCKED = "One of your links is blocked";
    private static final String LINK_BEING_CHECKED = "One of your links is being checked";
    private static final String RECENT_BLOCK = "One of your links was blocked. Available from %s";

    private final UrlRepository urlRepository;
    private final LinkReviewRepository linkReviewRepository;
    private final SignInMethodService signInMethodService;
    private final Clock clock;

    @Override
    public AccountStanding standingOf(final User user) {
        final var now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
        final var missing = new ArrayList<MissingRequirement>();
        if (!Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            missing.add(MissingRequirement.of(RequirementCode.TWO_STEP_SIGN_IN, TURN_ON_TWO_STEP));
        }
        final var oldEnoughFrom = user.getCreateDate() == null ? now : user.getCreateDate().plus(MINIMUM_ACCOUNT_AGE);
        if (now.isBefore(oldEnoughFrom)) {
            missing.add(new MissingRequirement(RequirementCode.ACCOUNT_AGE, AVAILABLE_FROM.formatted(DAY.format(oldEnoughFrom)), oldEnoughFrom));
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified()) && !signsInWithAProvider(user)) {
            missing.add(MissingRequirement.of(RequirementCode.VERIFIED_EMAIL, VERIFY_EMAIL));
        }
        final var blockedNow = urlRepository.ownerHasBlockedLink(user.getId(), BlockReason.HOLDS);
        if (blockedNow) {
            missing.add(MissingRequirement.of(RequirementCode.NO_BLOCKED_LINKS, LINK_BLOCKED));
        }
        if (urlRepository.ownerHasHeldLink(user.getId(), BlockReason.HOLDS) || linkReviewRepository.existsByUrl_Owner_IdAndResolvedAtIsNull(user.getId())) {
            missing.add(MissingRequirement.of(RequirementCode.NO_LINKS_BEING_CHECKED, LINK_BEING_CHECKED));
        }
        final var lastBlock = user.getLastLinkBlockedAt();
        if (!blockedNow && lastBlock != null && now.isBefore(lastBlock.plus(BLOCK_COOLDOWN))) {
            final var clearFrom = lastBlock.plus(BLOCK_COOLDOWN);
            missing.add(new MissingRequirement(RequirementCode.NO_RECENT_BLOCKS, RECENT_BLOCK.formatted(DAY.format(clearFrom)), clearFrom));
        }
        return new AccountStanding(List.copyOf(missing));
    }

    private boolean signsInWithAProvider(final User user) {
        return signInMethodService.methodsOf(user.getId()).stream()
                .anyMatch(method -> PROVIDERS.contains(method.method()) && method.turnedOffAt() == null);
    }

}
