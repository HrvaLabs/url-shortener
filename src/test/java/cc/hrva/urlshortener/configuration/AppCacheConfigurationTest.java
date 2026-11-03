package cc.hrva.urlshortener.configuration;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AppCacheConfigurationTest {

    private final AppCacheConfiguration appCacheConfiguration = new AppCacheConfiguration(JsonMapper.builder().build());

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, CacheAutoConfiguration.class))
            .withUserConfiguration(AppCacheConfiguration.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("spring.data.redis.host=localhost", "spring.cache.cache-names=urls");

    @Test
    void otherCachesAreForgottenAfterAnHour() {
        final var cacheConfiguration = appCacheConfiguration.cacheConfiguration();

        assertThat(cacheConfiguration.getTtlFunction().getTimeToLive("promo", null)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void theUrlsCacheKeepsEntriesForADay() {
        final var defaults = appCacheConfiguration.cacheConfiguration();
        final var builder = RedisCacheManager.builder(mock(RedisConnectionFactory.class)).cacheDefaults(defaults);

        appCacheConfiguration.urlsCacheKeepsEntriesForADay().customize(builder);

        final var urls = builder.getCacheConfigurationFor("urls").orElseThrow();
        assertThat(urls.getTtlFunction().getTimeToLive("abc", null)).isEqualTo(Duration.ofHours(24));
        assertThat(urls.getAllowCacheNullValues()).isFalse();
        assertThat(urls.getValueSerializationPair()).isSameAs(defaults.getValueSerializationPair());
    }

    @Test
    void theAutoConfiguredCacheManagerKeepsLinksADayAndOtherCachesAnHour() {
        contextRunner.run(context -> {
            final var cacheManager = context.getBean(RedisCacheManager.class);

            assertThat(cacheManager.getCacheConfigurations().get("urls").getTtlFunction().getTimeToLive("abc", null)).isEqualTo(Duration.ofHours(24));
            assertThat(context.getBean(RedisCacheConfiguration.class).getTtlFunction().getTimeToLive("promo", null)).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void otherCachesKeepTheHour() {
        final var builder = RedisCacheManager.builder(mock(RedisConnectionFactory.class)).cacheDefaults(appCacheConfiguration.cacheConfiguration());

        appCacheConfiguration.urlsCacheKeepsEntriesForADay().customize(builder);

        assertThat(builder.getCacheConfigurationFor("promo")).isEmpty();
        assertThat(builder.build().getCacheConfigurations()).doesNotContainKey("promo");
    }

}
