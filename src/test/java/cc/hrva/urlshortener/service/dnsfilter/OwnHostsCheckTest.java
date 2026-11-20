package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.OwnHostBlocked;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider.QUAD9;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.BLOCKED;
import static cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus.NOT_BLOCKED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnHostsCheckTest {

    @Mock
    private DnsFilterService dnsFilterService;

    @Mock
    private CustomDomainRepository customDomainRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OwnHostsCheck ownHostsCheck;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc/");
        ownHostsCheck = new OwnHostsCheck(dnsFilterService, customDomainRepository, appProperties, eventPublisher);
        when(customDomainRepository.findByStatusIn(Set.of(CustomDomainStatus.ACTIVE))).thenReturn(List.of(CustomDomain.builder().hostname("go.example.com").build()));
    }

    @Test
    void checksTheServerTheFrontendAndEveryActiveCustomDomain() {
        when(dnsFilterService.lookup(any())).thenAnswer(invocation -> clean(invocation.getArgument(0)));

        ownHostsCheck.run();

        verify(dnsFilterService, times(1)).lookup("hrva.cc");
        verify(dnsFilterService, times(1)).lookup("app.hrva.cc");
        verify(dnsFilterService, times(1)).lookup("go.example.com");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aBlockedOwnHostPublishesOneAlert() {
        when(dnsFilterService.lookup(any())).thenAnswer(invocation -> clean(invocation.getArgument(0)));
        when(dnsFilterService.lookup("app.hrva.cc")).thenReturn(new DnsFilterReport("app.hrva.cc", true,
                List.of(new DnsFilterAnswer(QUAD9, BLOCKED, null), new DnsFilterAnswer(DnsFilterProvider.CLOUDFLARE, NOT_BLOCKED, null))));

        ownHostsCheck.run();

        verify(eventPublisher).publishEvent(new OwnHostBlocked("app.hrva.cc", List.of("Quad9")));
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aHostThatDoesNotExistAndALookupThatFailsPublishNothingAndTheRestIsStillChecked() {
        when(dnsFilterService.lookup("hrva.cc")).thenThrow(new IllegalStateException("resolver down"));
        when(dnsFilterService.lookup("app.hrva.cc")).thenReturn(new DnsFilterReport("app.hrva.cc", false, List.of(new DnsFilterAnswer(QUAD9, BLOCKED, null))));
        when(dnsFilterService.lookup("go.example.com")).thenReturn(clean("go.example.com"));

        ownHostsCheck.run();

        verify(dnsFilterService).lookup("go.example.com");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private static DnsFilterReport clean(final String host) {
        return new DnsFilterReport(host, true, List.of(new DnsFilterAnswer(QUAD9, NOT_BLOCKED, null)));
    }

}
