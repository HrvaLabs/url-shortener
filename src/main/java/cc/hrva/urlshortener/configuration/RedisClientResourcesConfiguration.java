package cc.hrva.urlshortener.configuration;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisClientResourcesConfiguration {

    @Bean
    public ClientResourcesBuilderCustomizer jdkAddressResolver() {
        return builder -> builder.addressResolverGroup(DefaultAddressResolverGroup.INSTANCE);
    }

}
