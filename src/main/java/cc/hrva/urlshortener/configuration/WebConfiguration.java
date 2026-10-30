package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.security.CustomHostFilter;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

@Configuration
public class WebConfiguration {

    private static final int CUSTOM_HOST_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }

    @Bean
    public FilterRegistrationBean<CustomHostFilter> customHostFilterRegistration(final CustomHostFilter customHostFilter) {
        final var registration = new FilterRegistrationBean<>(customHostFilter);
        registration.setOrder(CUSTOM_HOST_FILTER_ORDER);
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        return registration;
    }

}
