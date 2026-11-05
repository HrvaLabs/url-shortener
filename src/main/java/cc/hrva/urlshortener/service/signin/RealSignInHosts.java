package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RealSignInHosts {

    private final BlocklistSourceRepository blocklistSourceRepository;
    private final BlocklistStore blocklistStore;

    public boolean isRealSignInHost(final String host) {
        final var listedForm = listedFormOf(host).orElse(null);
        if (listedForm == null || DomainNames.isIpLiteral(listedForm) || DomainNames.isUnderPrivateSuffix(listedForm)) {
            return false;
        }
        final var lookup = List.of(listedForm);
        try {
            final var trustedSources = blocklistSourceRepository.findByEnabledTrue().stream()
                    .filter(source -> source.getAction() == BlocklistAction.TRUST && source.getKind() == SourceKind.TOP_SITES)
                    .collect(Collectors.toMap(BlocklistSource::getId, source -> lookup));
            return !blocklistStore.matchingSources(trustedSources).isEmpty();
        } catch (final RuntimeException exception) {
            log.warn("Real sign-in host lookup failed, the sign-in form counts exception={}", exception.getClass().getName());
            return false;
        }
    }

    private static Optional<String> listedFormOf(final String host) {
        if (StringUtils.isBlank(host)) {
            return Optional.empty();
        }
        try {
            return Optional.of(DomainNames.toAscii(StringUtils.stripEnd(host.strip(), "."))).filter(StringUtils::isNotEmpty);
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

}
