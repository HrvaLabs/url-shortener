package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainRouteRow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostRoutingTest {

    private static final String CYRILLIC_O = Character.toString(0x43E);

    private final CustomDomainRepository customDomainRepository = mock(CustomDomainRepository.class);

    private HostRouting hostRouting;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.getDomains().setPrimaryHosts(List.of("domains.hrva.cc", "localhost", "127.0.0.1"));
        when(customDomainRepository.findRoutes()).thenReturn(List.of(
                new DomainRouteRow(7L, "go.example.com", "go.example.com", CustomDomainStatus.ACTIVE, 5L, true),
                new DomainRouteRow(8L, "xn--g-1tb.example.com", "xn--g-1tb.example.com", CustomDomainStatus.ACTIVE, 5L, null),
                new DomainRouteRow(9L, "off.example.com", "off.example.com", CustomDomainStatus.DISABLED, 5L, true)));
        final var registry = new CustomDomainRegistry(customDomainRepository,
                new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:host-routing")));
        registry.reload();
        hostRouting = new HostRouting(new CustomDomainHostnames(appProperties), registry);
    }

    @Test
    void theServerUrlHostAndThePrimaryHostsArePrimary() {
        for (final var host : List.of("hrva.cc", "domains.hrva.cc", "localhost", "127.0.0.1")) {
            assertThat(hostRouting.classify(host)).as(host).isInstanceOf(HostRouting.Primary.class);
        }
    }

    @Test
    void aPrimaryHostIsPrimaryWithAPortATrailingDotOrCapitals() {
        for (final var host : List.of("HRVA.cc", "hrva.cc.", "hrva.cc:443", "Domains.HRVA.cc.:8443", "localhost:18087")) {
            assertThat(hostRouting.classify(host)).as(host).isInstanceOf(HostRouting.Primary.class);
        }
    }

    @Test
    void aRegisteredHostnameIsCustomWithItsRoute() {
        assertThat(hostRouting.classify("go.example.com"))
                .isEqualTo(new HostRouting.Custom(new DomainRoute(7L, "go.example.com", CustomDomainStatus.ACTIVE, true)));
    }

    @Test
    void aCustomHostIsFoundWithAPortATrailingDotCapitalsOrInUnicode() {
        for (final var host : List.of("GO.Example.COM", "go.example.com.", "go.example.com:8443", "Go.Example.Com.:443")) {
            assertThat(hostRouting.classify(host)).as(host)
                    .isEqualTo(new HostRouting.Custom(new DomainRoute(7L, "go.example.com", CustomDomainStatus.ACTIVE, true)));
        }
        assertThat(hostRouting.classify("g" + CYRILLIC_O + ".example.com"))
                .isEqualTo(new HostRouting.Custom(new DomainRoute(8L, "xn--g-1tb.example.com", CustomDomainStatus.ACTIVE, true)));
    }

    @Test
    void aTurnedOffDomainIsStillCustomSoItsVisitorsAreToldWhy() {
        assertThat(hostRouting.classify("off.example.com"))
                .isEqualTo(new HostRouting.Custom(new DomainRoute(9L, "off.example.com", CustomDomainStatus.DISABLED, true)));
    }

    @Test
    void anyOtherHostIsUnknown() {
        final var hosts = new ArrayList<>(List.of("example.org", "test.karlo.cc", "www.go.example.com", "evil.hrva.cc",
                "hrva.cc.evil.com", "go.example.com..", "", " "));
        hosts.add(null);
        for (final var host : hosts) {
            assertThat(hostRouting.classify(host)).as(String.valueOf(host)).isInstanceOf(HostRouting.Unknown.class);
        }
    }

}
