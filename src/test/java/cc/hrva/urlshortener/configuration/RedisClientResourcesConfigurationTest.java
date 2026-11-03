package cc.hrva.urlshortener.configuration;

import io.lettuce.core.resource.ClientResources;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClientResourcesConfigurationTest {

    @Test
    void lettuceUsesTheJdkResolver() {
        final var builder = ClientResources.builder();

        new RedisClientResourcesConfiguration().jdkAddressResolver().customize(builder);

        final var resources = builder.build();
        try {
            assertThat(resources.addressResolverGroup()).isSameAs(DefaultAddressResolverGroup.INSTANCE);
        } finally {
            resources.shutdown().syncUninterruptibly();
        }
    }

}
