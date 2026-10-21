package cc.hrva.urlshortener.configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainReputationConfiguration {

    @Bean
    public Function<String, List<InetAddress>> dblResolver() {
        return name -> {
            try {
                return List.of(InetAddress.getAllByName(name));
            } catch (final UnknownHostException exception) {
                return List.of();
            }
        };
    }

}
