package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AccountStanding;
import cc.hrva.urlshortener.dto.MissingRequirement;
import cc.hrva.urlshortener.dto.SignInMethodResponse;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
        return standing(user, new Links(
                urlRepository.ownerHasBlockedLink(user.getId(), BlockReason.HOLDS),
                urlRepository.ownerHasHeldLink(user.getId(), BlockReason.HOLDS) || linkReviewRepository.existsByUrl_Owner_IdAndResolvedAtIsNullAndSystemFlaggedTrue(user.getId())),
                signInMethodService.methodsOf(user.getId()));
    }

    @Override
    public Map<Long, AccountStanding> standingsOf(final Collection<User> users) {
        if (users.isEmpty()) {
            return Map.of();
        }
        final var ids = users.stream().map(User::getId).toList();
        final var blocked = Set.copyOf(urlRepository.ownersWithBlockedLinks(ids, BlockReason.HOLDS));
        final var beingChecked = new HashSet<>(urlRepository.ownersWithHeldLinks(ids, BlockReason.HOLDS));
        beingChecked.addAll(linkReviewRepository.findOwnerIdsWithOpenSystemReviews(ids));
        final var methods = signInMethodService.methodsOf(ids);
        return users.stream().collect(Collectors.toMap(User::getId, user -> standing(user,
                new Links(blocked.contains(user.getId()), beingChecked.contains(user.getId())), methods.getOrDefault(user.getId(), List.of()))));
    }

    private AccountStanding standing(final User user, final Links links, final List<SignInMethodResponse> methods) {
        final var now = LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
        final var missing = new ArrayList<MissingRequirement>();
        if (!Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            missing.add(MissingRequirement.of(RequirementCode.TWO_STEP_SIGN_IN, TURN_ON_TWO_STEP));
        }
        final var oldEnoughFrom = user.getCreateDate() == null ? now : user.getCreateDate().plus(MINIMUM_ACCOUNT_AGE);
        if (now.isBefore(oldEnoughFrom)) {
            missing.add(new MissingRequirement(RequirementCode.ACCOUNT_AGE, AVAILABLE_FROM.formatted(DAY.format(oldEnoughFrom)), oldEnoughFrom));
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified()) && !signsInWithAProvider(methods)) {
            missing.add(MissingRequirement.of(RequirementCode.VERIFIED_EMAIL, VERIFY_EMAIL));
        }
        if (links.blocked()) {
            missing.add(MissingRequirement.of(RequirementCode.NO_BLOCKED_LINKS, LINK_BLOCKED));
        }
        if (links.beingChecked()) {
            missing.add(MissingRequirement.of(RequirementCode.NO_LINKS_BEING_CHECKED, LINK_BEING_CHECKED));
        }
        final var lastBlock = user.getLastLinkBlockedAt();
        if (!links.blocked() && lastBlock != null && now.isBefore(lastBlock.plus(BLOCK_COOLDOWN))) {
            final var clearFrom = lastBlock.plus(BLOCK_COOLDOWN);
            missing.add(new MissingRequirement(RequirementCode.NO_RECENT_BLOCKS, RECENT_BLOCK.formatted(DAY.format(clearFrom)), clearFrom));
        }
        return new AccountStanding(List.copyOf(missing));
    }

    private static boolean signsInWithAProvider(final List<SignInMethodResponse> methods) {
        return methods.stream().anyMatch(method -> PROVIDERS.contains(method.method()) && method.turnedOffAt() == null);
    }

    private record Links(boolean blocked, boolean beingChecked) {}

}
