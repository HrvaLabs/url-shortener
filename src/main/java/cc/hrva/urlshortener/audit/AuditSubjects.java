package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class AuditSubjects {

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainRequestRepository customDomainRequestRepository;
    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final ShortLinks shortLinks;
    private final TransactionTemplate readOnly;
    private final Map<String, LongFunction<Optional<String>>> lookups;

    public AuditSubjects(final CustomDomainRepository customDomainRepository, final CustomDomainRequestRepository customDomainRequestRepository,
                         final UrlRepository urlRepository, final UserRepository userRepository, final ShortLinks shortLinks,
                         final PlatformTransactionManager transactionManager) {
        this.customDomainRepository = customDomainRepository;
        this.customDomainRequestRepository = customDomainRequestRepository;
        this.urlRepository = urlRepository;
        this.userRepository = userRepository;
        this.shortLinks = shortLinks;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.lookups = Map.of(
                "CustomDomain", this::domain,
                "DomainRequest", this::request,
                "Url", this::link,
                "User", this::account);
    }

    public Optional<String> describe(final String targetType, final String targetIdentifier) {
        final var lookup = lookups.get(targetType);
        if (lookup == null) {
            return Optional.empty();
        }
        try {
            final var id = Long.parseLong(targetIdentifier);
            return readOnly.execute(status -> lookup.apply(id));
        } catch (final RuntimeException e) {
            log.warn("Couldn't name the {} an audit row acts on", targetType, e);
            return Optional.empty();
        }
    }

    private Optional<String> domain(final long id) {
        return customDomainRepository.findAuditSubjectById(id).map(domain -> domain.ownerEmail() == null
                ? "domain: " + domain.hostname()
                : "domain: " + domain.hostname() + ", owner: " + domain.ownerEmail());
    }

    private Optional<String> request(final long id) {
        return customDomainRequestRepository.findAuditSubjectById(id)
                .map(request -> "account: " + request.email() + ", limit: " + request.limitAtRequest() + " → " + request.requestedTotal());
    }

    private Optional<String> link(final long id) {
        return urlRepository.findAuditSubjectById(id)
                .map(link -> "link: " + shortLinks.display(link.hostname(), link.shortUrl()) + ", destination: " + link.longUrl());
    }

    private Optional<String> account(final long id) {
        return userRepository.findEmailById(id).map(email -> "account: " + email);
    }

}
