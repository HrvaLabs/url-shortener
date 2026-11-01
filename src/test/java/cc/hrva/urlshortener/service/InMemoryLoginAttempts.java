package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.impl.DefaultLoginAttemptService;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;

public final class InMemoryLoginAttempts {

    private InMemoryLoginAttempts() {}

    public static DefaultLoginAttemptService withMaxAttempts(final long maxAttempts) {
        return over(new InMemoryRedis(), maxAttempts);
    }

    public static DefaultLoginAttemptService over(final InMemoryRedis redis, final long maxAttempts) {
        final var redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        final var properties = new AppProperties();
        properties.setMaxLoginAttempts(maxAttempts);

        return new DefaultLoginAttemptService(properties, new RedisFixedWindowRateLimiter(redisTemplate), redisTemplate);
    }

}
