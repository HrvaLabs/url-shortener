package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.security.MetricsTokenFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@RequiredArgsConstructor
public class MetricsSecurityConfiguration {

    public static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private final AppProperties appProperties;

    @Bean
    @Order(0)
    public SecurityFilterChain metricsSecurityFilterChain(final HttpSecurity http) throws Exception {
        http.securityMatcher(PROMETHEUS_PATH, PROMETHEUS_PATH + "/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(new MetricsTokenFilter(appProperties), AuthorizationFilter.class)
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

}
