package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ScammerSignalService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultScammerSignalService implements ScammerSignalService {

    private static final Duration YOUNG_ACCOUNT = Duration.ofDays(7);
    private static final long FLAGGED_OR_BLOCKED_LINKS_WHILE_YOUNG = 2;
    private static final long BLOCKED_LINKS = 3;
    private static final Duration SIGNUP_BURST_WINDOW = Duration.ofHours(24);
    private static final long LINKS_IN_SIGNUP_BURST = 10;

    private final UrlRepository urlRepository;
    private final Clock clock;

    @Override
    public Set<Long> possibleScammers(final Collection<User> users) {
        final var ids = users.stream().map(User::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Set.of();
        }
        final var possibleScammers = possibleScammerIds();
        return ids.stream().filter(possibleScammers::contains).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<Long> possibleScammerIds() {
        final var youngSince = LocalDateTime.now(clock.withZone(ZoneId.systemDefault())).minus(YOUNG_ACCOUNT);
        return Stream.of(
                        urlRepository.findOwnerIdsSignedUpAfterWithFlaggedOrBlockedLinks(youngSince, FLAGGED_OR_BLOCKED_LINKS_WHILE_YOUNG),
                        urlRepository.findOwnerIdsWithBlockedLinks(BLOCKED_LINKS),
                        urlRepository.findOwnerIdsWithLinksSoonAfterSignup(SIGNUP_BURST_WINDOW.toSeconds(), LINKS_IN_SIGNUP_BURST))
                .flatMap(List::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

}
