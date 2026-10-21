package cc.hrva.urlshortener.configuration;

import io.lettuce.core.ClientOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withUserConfiguration(RedisClientConfiguration.class)
            .withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.timeout=3s");

    @Test
    void aDroppedConnectionRejectsCommandsAtOnceAndKeepsReconnecting() {
        final var clientOptions = ClientOptions.builder();

        new RedisClientConfiguration().rejectCommandsWhileDisconnected().customize(clientOptions);

        final var options = clientOptions.build();
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.isAutoReconnect()).isTrue();
    }

    @Test
    void theRedisClientKeepsTheAutoConfiguredOptionsAndRejectsCommandsWhileDisconnected() {
        contextRunner.run(context -> {
            final var clientConfiguration = context.getBean(LettuceConnectionFactory.class).getClientConfiguration();
            final var options = clientConfiguration.getClientOptions().orElseThrow();

            assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(options.isAutoReconnect()).isTrue();
            assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
            assertThat(clientConfiguration.getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

}
