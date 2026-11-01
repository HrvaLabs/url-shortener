package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LoginAttemptResponse;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import cc.hrva.urlshortener.util.InMemoryPages;
import cc.hrva.urlshortener.util.SortWhitelist;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLoginAttemptService implements LoginAttemptService {

    private static final String KEY_PREFIX = "login:";
    private static final String NETWORK = KEY_PREFIX + "network";
    private static final String ACCOUNT = KEY_PREFIX + "account";
    private static final String STORED_PREFIX = RedisFixedWindowRateLimiter.KEY_PREFIX + KEY_PREFIX;
    private static final Duration ACCOUNT_LOCK_WINDOW = Duration.ofDays(1);
    private static final int SCAN_BATCH = 500;
    private static final int MOST_KEYS_LISTED = 10_000;
    private static final String TOO_MANY_SIGN_INS = "Too many sign-in attempts. Try again in %s.";
    private static final String ATTEMPTS = "attempts";
    private static final String KEY = "key";
    private static final SortWhitelist ATTEMPT_SORTS = SortWhitelist.of(ATTEMPTS, KEY)
            .byDefault(Sort.by(Sort.Order.desc(ATTEMPTS)))
            .thenBy(Sort.Order.asc(KEY));

    private final AppProperties appProperties;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void checkSignIn(final String clientIp, final String email) {
        refuseWhileBlocked(Stream.concat(Stream.of(networkLimit(clientIp)), accountLimit(email).stream()));
    }

    @Override
    public void checkNetwork(final String clientIp) {
        refuseWhileBlocked(Stream.of(networkLimit(clientIp)));
    }

    @Override
    public void signInFailed(final String clientIp, final String email) {
        count(networkLimit(clientIp));
        accountLimit(email).ifPresent(this::count);
    }

    @Override
    public void networkFailed(final String clientIp) {
        count(networkLimit(clientIp));
    }

    @Override
    public void signInSucceeded(final String email) {
        accountLimit(email).ifPresent(limit -> forget(limit.key()));
    }

    @Override
    public void loginFailed(final String key) {
        count(accountLockOf(key));
    }

    @Override
    public boolean isBlocked(final String key) {
        return blockedFor(accountLockOf(key)).isPresent();
    }

    @Override
    public void loginSucceeded(final String key) {
        forget(KEY_PREFIX + key);
    }

    @Override
    public Page<LoginAttemptResponse> attempts(final String search, final Pageable pageable) {
        final var order = ATTEMPT_SORTS.sortOf(pageable.getSort()).stream()
                .map(DefaultLoginAttemptService::comparatorOf)
                .reduce(Comparator::thenComparing)
                .orElseThrow();
        final var matches = matcherOf(search);
        final var keys = storedKeys();
        final var counts = keys.isEmpty() ? List.<String>of() : inRedis(() -> redisTemplate.opsForValue().multiGet(keys), List.<String>of());
        final var rows = new ArrayList<LoginAttemptResponse>();
        for (var i = 0; i < keys.size() && i < counts.size(); i++) {
            final var shownKey = StringUtils.removeStart(keys.get(i), STORED_PREFIX);
            final var attempts = attemptsOf(counts.get(i));
            if (attempts > 0 && matches.test(shownKey)) {
                rows.add(new LoginAttemptResponse(shownKey, attempts));
            }
        }
        rows.sort(order);

        return InMemoryPages.of(rows, pageable);
    }

    @Override
    public void clearLoginAttempts() {
        final var keys = storedKeys();
        if (!keys.isEmpty()) {
            inRedis(() -> redisTemplate.delete(keys), 0L);
        }
        log.info("Cleared login rate limits keys={}", keys.size());
    }

    private void refuseWhileBlocked(final Stream<Limit> limits) {
        final var wait = limits.map(this::blockedFor)
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder());
        if (wait.isPresent()) {
            log.info("Sign-in refused by the login limit retryAfterSeconds={}", wait.get().toSeconds());
            throw new RateLimitExceededException(TOO_MANY_SIGN_INS.formatted(minutesOf(wait.get())), wait.get());
        }
    }

    private Optional<Duration> blockedFor(final Limit limit) {
        final var storedKey = RedisFixedWindowRateLimiter.KEY_PREFIX + limit.key();
        final var attempts = attemptsOf(inRedis(() -> redisTemplate.opsForValue().get(storedKey), null));
        if (attempts < limit.attempts()) {
            return Optional.empty();
        }

        final var millisLeft = inRedis(() -> redisTemplate.getExpire(storedKey, TimeUnit.MILLISECONDS), null);
        return Optional.of(millisLeft != null && millisLeft > 0 ? Duration.ofMillis(millisLeft) : limit.window());
    }

    private void count(final Limit limit) {
        try {
            rateLimiter.increment(limit.key(), limit.window());
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Login limit unavailable, the attempt wasn't counted");
        }
    }

    private void forget(final String key) {
        inRedis(() -> redisTemplate.delete(RedisFixedWindowRateLimiter.KEY_PREFIX + key), false);
    }

    private List<String> storedKeys() {
        final var options = ScanOptions.scanOptions().match(STORED_PREFIX + "*").count(SCAN_BATCH).build();

        return inRedis(() -> {
            final var keys = new ArrayList<String>();
            try (var cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext() && keys.size() < MOST_KEYS_LISTED) {
                    keys.add(cursor.next());
                }
            }
            return keys;
        }, List.of());
    }

    private Limit networkLimit(final String clientIp) {
        return new Limit(RateLimiter.clientKey(NETWORK, StringUtils.defaultString(clientIp)), appProperties.getMaxLoginAttempts(),
                appProperties.getLoginLimit().getNetworkWindow());
    }

    private Optional<Limit> accountLimit(final String email) {
        if (StringUtils.isBlank(email)) {
            return Optional.empty();
        }

        final var limit = appProperties.getLoginLimit();
        return Optional.of(new Limit(RateLimiter.hashedKey(ACCOUNT, normalised(email)), limit.getAccountAttempts(), limit.getAccountWindow()));
    }

    private Limit accountLockOf(final String key) {
        return new Limit(KEY_PREFIX + key, appProperties.getMaxLoginAttempts(), ACCOUNT_LOCK_WINDOW);
    }

    private static Predicate<String> matcherOf(final String search) {
        if (StringUtils.isBlank(search)) {
            return key -> true;
        }

        final var term = search.strip().toLowerCase(Locale.ROOT);
        final var network = StringUtils.removeStart(RateLimiter.clientKey(NETWORK, search.strip()), KEY_PREFIX);
        final var account = StringUtils.removeStart(RateLimiter.hashedKey(ACCOUNT, normalised(search)), KEY_PREFIX);
        return key -> key.toLowerCase(Locale.ROOT).contains(term) || key.equals(network) || key.equals(account);
    }

    private static String normalised(final String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static int attemptsOf(final String stored) {
        try {
            return stored == null ? 0 : Integer.parseInt(stored);
        } catch (final NumberFormatException exception) {
            return 0;
        }
    }

    private static String minutesOf(final Duration wait) {
        final var minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    private static <T> T inRedis(final Supplier<T> command, final T whenUnavailable) {
        try {
            return command.get();
        } catch (final DataAccessException exception) {
            log.warn("Login limit unavailable, letting the attempt through exception={}", exception.getClass().getName());
            return whenUnavailable;
        }
    }

    private static Comparator<LoginAttemptResponse> comparatorOf(final Sort.Order order) {
        final Comparator<LoginAttemptResponse> ascending = ATTEMPTS.equals(order.getProperty())
                ? Comparator.comparingInt(LoginAttemptResponse::attempts)
                : Comparator.comparing(LoginAttemptResponse::key);
        return order.isAscending() ? ascending : ascending.reversed();
    }

    private record Limit(String key, long attempts, Duration window) {
    }

}
