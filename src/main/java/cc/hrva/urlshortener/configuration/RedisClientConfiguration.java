package cc.hrva.urlshortener.configuration;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisClientConfiguration {

    @Bean
    public LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return clientOptions -> clientOptions.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

}
