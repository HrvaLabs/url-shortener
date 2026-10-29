package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.client.Resolver;
import cc.hrva.urlshortener.service.notification.HostPacer;
import cc.hrva.urlshortener.service.notification.NetworkCanary;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DestinationCheckConfiguration {

    @Bean
    public NetworkCanary networkCanary(final Clock clock) {
        return new NetworkCanary(Resolver.SYSTEM, clock);
    }

    @Bean
    public HostPacer.Sleeper hostPacerSleeper() {
        return Thread::sleep;
    }

}
