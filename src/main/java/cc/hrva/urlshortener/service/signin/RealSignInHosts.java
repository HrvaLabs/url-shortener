package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.List;
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
        if (StringUtils.isBlank(host) || DomainNames.isIpLiteral(host) || DomainNames.isUnderPrivateSuffix(host)) {
            return false;
        }
        final var domain = List.of(DomainNames.registrableDomain(host));
        try {
            final var trustedSources = blocklistSourceRepository.findByEnabledTrue().stream()
                    .filter(source -> source.getAction() == BlocklistAction.TRUST)
                    .collect(Collectors.toMap(BlocklistSource::getId, source -> domain));
            return !blocklistStore.matchingSources(trustedSources).isEmpty();
        } catch (final RuntimeException exception) {
            log.warn("Real sign-in host lookup failed, the sign-in form counts exception={}", exception.getClass().getName());
            return false;
        }
    }

}
