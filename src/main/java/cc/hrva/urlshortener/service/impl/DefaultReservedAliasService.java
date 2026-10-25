package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import cc.hrva.urlshortener.dto.ReservedAliasResponse.InUseLink;
import cc.hrva.urlshortener.exception.ReservedAliasNotFoundException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.model.ReservedAlias;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.ReservedAliasRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.UserService;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultReservedAliasService implements ReservedAliasService {

    private static final List<String> BUILT_IN_ALIASES = List.of(
            "actuator", "api", "auth", "error", "login", "logout", "mcp", "oauth2", "swagger-ui", "v3", "webjars");
    private static final String BUILT_IN_REASON = "Used by hrva.cc itself";
    private static final String ALREADY_RESERVED = "This alias is already reserved.";
    private static final String RESERVED_ALIAS_NOT_FOUND = "Reserved alias not found";

    private final ReservedAliasRepository reservedAliasRepository;
    private final UrlRepository urlRepository;
    private final UserService userService;

    @Override
    public boolean isReserved(final String alias) {
        if (alias == null) {
            return false;
        }
        final var normalizedAlias = normalized(alias);
        return BUILT_IN_ALIASES.contains(normalizedAlias) || reservedAliasRepository.existsByAlias(normalizedAlias);
    }

    @Override
    public List<ReservedAliasResponse> list() {
        final var customAliases = reservedAliasRepository.findAllByOrderByAliasAsc();
        final var aliases = Stream.concat(BUILT_IN_ALIASES.stream(), customAliases.stream().map(ReservedAlias::getAlias)).toList();
        final var linksByAlias = linksUsing(aliases);
        return Stream.concat(
                        BUILT_IN_ALIASES.stream().map(alias -> builtInEntry(alias, linksByAlias.getOrDefault(alias, List.of()))),
                        customAliases.stream()
                                .map(entry -> ReservedAliasResponse.from(entry, linksByAlias.getOrDefault(entry.getAlias(), List.of()))))
                .toList();
    }

    @Override
    @Transactional
    public ReservedAliasResponse reserve(final ReservedAliasDto reservedAliasDto) {
        final var alias = normalized(reservedAliasDto.alias());
        if (isReserved(alias)) {
            throw new ShortUrlAlreadyExistsException(ALREADY_RESERVED);
        }
        final var saved = save(ReservedAlias.builder()
                .alias(alias)
                .reason(reservedAliasDto.reason().trim())
                .createdBy(userService.getUserFromToken().getEmail())
                .build());
        log.info("Alias reserved id={}", saved.getId());
        return ReservedAliasResponse.from(saved, linksUsing(List.of(alias)).getOrDefault(alias, List.of()));
    }

    @Override
    @Transactional
    public void unreserve(final long id) {
        final var reservedAlias = reservedAliasRepository.findById(id)
                .orElseThrow(() -> new ReservedAliasNotFoundException(RESERVED_ALIAS_NOT_FOUND));
        reservedAliasRepository.delete(reservedAlias);
        log.info("Alias unreserved id={}", id);
    }

    private ReservedAlias save(final ReservedAlias reservedAlias) {
        try {
            return reservedAliasRepository.save(reservedAlias);
        } catch (final DataIntegrityViolationException exception) {
            log.info("Alias was reserved by a concurrent request");
            throw new ShortUrlAlreadyExistsException(ALREADY_RESERVED);
        }
    }

    private Map<String, List<InUseLink>> linksUsing(final Collection<String> aliases) {
        return urlRepository.findByLowerShortUrlIn(aliases).stream()
                .sorted(Comparator.comparing(Url::getId))
                .collect(Collectors.groupingBy(url -> url.getShortUrl().toLowerCase(Locale.ROOT),
                        Collectors.mapping(InUseLink::from, Collectors.toList())));
    }

    private static ReservedAliasResponse builtInEntry(final String alias, final List<InUseLink> inUse) {
        return new ReservedAliasResponse(null, alias, BUILT_IN_REASON, true, null, null, inUse);
    }

    private static String normalized(final String alias) {
        return alias.trim().toLowerCase(Locale.ROOT);
    }

}
