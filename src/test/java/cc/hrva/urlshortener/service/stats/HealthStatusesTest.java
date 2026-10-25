package cc.hrva.urlshortener.service.stats;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthStatusesTest {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void noHealthEndpointIsUnknown() {
        assertThat(healthStatuses().status("db")).isEqualTo("UNKNOWN");
    }

    @Test
    void aMissingComponentIsUnknown() {
        final var healthEndpoint = mock(HealthEndpoint.class);
        when(healthEndpoint.healthForPath("redis")).thenReturn(null);
        beanFactory.registerSingleton("healthEndpoint", healthEndpoint);

        assertThat(healthStatuses().status("redis")).isEqualTo("UNKNOWN");
    }

    @Test
    void aFailingCheckIsUnknown() {
        final var healthEndpoint = mock(HealthEndpoint.class);
        when(healthEndpoint.healthForPath("redis")).thenThrow(new IllegalStateException("Timeout on blocking read"));
        beanFactory.registerSingleton("healthEndpoint", healthEndpoint);

        assertThat(healthStatuses().status("redis")).isEqualTo("UNKNOWN");
    }

    private HealthStatuses healthStatuses() {
        return new HealthStatuses(beanFactory.getBeanProvider(HealthEndpoint.class));
    }

}
