package cc.hrva.urlshortener.service.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HostRouting {

    private final CustomDomainHostnames customDomainHostnames;
    private final CustomDomainRegistry customDomainRegistry;

    public HostKind classify(final String serverName) {
        if (customDomainHostnames.isPrimary(serverName)) {
            return new Primary();
        }
        return customDomainRegistry.route(serverName).<HostKind>map(Custom::new).orElseGet(Unknown::new);
    }

    public sealed interface HostKind permits Primary, Custom, Unknown {
    }

    public record Primary() implements HostKind {
    }

    public record Custom(DomainRoute route) implements HostKind {
    }

    public record Unknown() implements HostKind {
    }

}
