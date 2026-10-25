package cc.hrva.urlshortener.service.stats;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthStatuses {

    private static final String UNKNOWN = Status.UNKNOWN.getCode();

    private final ObjectProvider<HealthEndpoint> healthEndpoint;

    public String status(final String component) {
        try {
            final var endpoint = healthEndpoint.getIfAvailable();
            if (endpoint == null) {
                return UNKNOWN;
            }
            final var health = endpoint.healthForPath(component);
            return health != null ? health.getStatus().getCode() : UNKNOWN;
        } catch (final RuntimeException exception) {
            log.warn("Failed to check the health of {}", component, exception);
            return UNKNOWN;
        }
    }

}
