package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditSubjects {

    private static final String PRIMARY_HOST = "hrva.cc";

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainRequestRepository customDomainRequestRepository;
    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final PlatformTransactionManager transactionManager;

    public Optional<String> describe(final String targetType, final String targetIdentifier) {
        if (!isDescribed(targetType)) {
            return Optional.empty();
        }
        try {
            final var id = Long.parseLong(targetIdentifier);
            final var readOnly = new TransactionTemplate(transactionManager);
            readOnly.setReadOnly(true);
            return readOnly.execute(status -> subject(targetType, id));
        } catch (final RuntimeException e) {
            log.warn("Couldn't name the {} an audit row acts on", targetType);
            return Optional.empty();
        }
    }

    private static boolean isDescribed(final String targetType) {
        return switch (targetType) {
            case "CustomDomain", "DomainRequest", "Url", "User" -> true;
            default -> false;
        };
    }

    private Optional<String> subject(final String targetType, final long id) {
        return switch (targetType) {
            case "CustomDomain" -> customDomainRepository.findAuditSubjectById(id).map(domain -> domain.ownerEmail() == null
                    ? "domain: " + domain.hostname()
                    : "domain: " + domain.hostname() + ", owner: " + domain.ownerEmail());
            case "DomainRequest" -> customDomainRequestRepository.findAuditSubjectById(id)
                    .map(request -> "account: " + request.email() + ", limit: " + request.limitAtRequest() + " → " + request.requestedTotal());
            case "Url" -> urlRepository.findAuditSubjectById(id).map(link -> "link: " + StringUtils.defaultIfBlank(link.hostname(), PRIMARY_HOST)
                    + "/" + link.shortUrl() + ", destination: " + link.longUrl());
            case "User" -> userRepository.findEmailById(id).map(email -> "account: " + email);
            default -> Optional.empty();
        };
    }

}
