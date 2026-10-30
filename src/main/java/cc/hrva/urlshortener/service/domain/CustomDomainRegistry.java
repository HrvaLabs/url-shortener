package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainRouteRow;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainRegistry implements SmartInitializingSingleton {

    private static final int READ_TIMEOUT_SECONDS = 5;

    private final CustomDomainRepository customDomainRepository;
    private final PlatformTransactionManager transactionManager;
    private final AtomicReference<Map<String, DomainRoute>> routes = new AtomicReference<>(Map.of());

    public Optional<DomainRoute> route(final String host) {
        return CustomDomainHostnames.withoutPort(host).map(routes.get()::get);
    }

    public Optional<DomainRoute> active(final String host) {
        return route(host).filter(DomainRoute::serves);
    }

    @Override
    public void afterSingletonsInstantiated() {
        reload();
    }

    public synchronized void reload() {
        try {
            final var rows = readTransaction().execute(status -> customDomainRepository.findRoutes());
            routes.set(routesOf(rows == null ? List.of() : rows));
        } catch (final RuntimeException exception) {
            log.warn("Could not reload the custom domain routes, keeping the previous ones exception={}", exception.getClass().getName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(final CustomDomainChangedEvent event) {
        reload();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountChange(final AccountActiveChangedEvent event) {
        reload();
    }

    private TransactionTemplate readTransaction() {
        final var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        template.setTimeout(READ_TIMEOUT_SECONDS);
        return template;
    }

    private static Map<String, DomainRoute> routesOf(final List<DomainRouteRow> rows) {
        return rows.stream()
                .filter(row -> keyOf(row) != null)
                .collect(Collectors.toUnmodifiableMap(CustomDomainRegistry::keyOf, CustomDomainRegistry::routeOf, (first, second) -> first));
    }

    private static String keyOf(final DomainRouteRow row) {
        return Optional.ofNullable(row.hostnameKey())
                .or(() -> CustomDomainHostnames.hostnameKey(row.hostname()))
                .orElse(null);
    }

    private static DomainRoute routeOf(final DomainRouteRow row) {
        return new DomainRoute(row.id(), row.hostname(), row.status(), row.ownerId() != null && !Objects.equals(row.ownerActive(), Boolean.FALSE));
    }

}
