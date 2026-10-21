package cc.hrva.urlshortener.configuration;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableCaching
@RequiredArgsConstructor
public class AppCacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AppCacheConfiguration.class);

    private final ObjectMapper objectMapper;

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJacksonJsonRedisSerializer(objectMapper)));
    }

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(final RuntimeException exception, final Cache cache, final Object key) {
                log.warn("Redis GET failed for key={}, falling back to DB", key, exception);
            }

            @Override
            public void handleCachePutError(final RuntimeException exception, final Cache cache, final Object key, final Object value) {
                log.warn("Redis PUT failed for key={}", key, exception);
            }

            @Override
            public void handleCacheEvictError(final RuntimeException exception, final Cache cache, final Object key) {
                log.warn("Redis EVICT failed for key={}", key, exception);
            }

            @Override
            public void handleCacheClearError(final RuntimeException exception, final Cache cache) {
                log.warn("Redis CLEAR failed", exception);
            }
        };
    }

}
