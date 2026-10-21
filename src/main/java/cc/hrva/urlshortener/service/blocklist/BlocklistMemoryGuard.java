package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BlocklistMemoryGuard {

    private static final long MEGABYTE = 1024L * 1024L;
    private static final long BYTES_PER_DOMAIN = 100;
    private static final long BYTES_PER_LINK = 200;
    private static final int MAX_MEMORY_PERCENT = 80;

    private final BlocklistStore blocklistStore;
    private final BlocklistSourceRepository blocklistSourceRepository;
    private final AppProperties appProperties;

    public Optional<String> refusal(final BlocklistSource source, final int entries) {
        final var estimate = entries * (source.getKind() == SourceKind.LINKS ? BYTES_PER_LINK : BYTES_PER_DOMAIN);
        final var memory = blocklistStore.memory();
        if (memory.maxMemory() > 0) {
            final var current = Objects.requireNonNullElse(blocklistStore.memoryBytes(source.getId()), 0L);
            final var limit = memory.maxMemory() / 100 * MAX_MEMORY_PERCENT;
            return memory.usedMemory() + estimate - current > limit
                    ? Optional.of(notLoaded(estimate) + " would take Redis past " + MAX_MEMORY_PERCENT + " % of its "
                            + megabytes(memory.maxMemory()) + " MB maxmemory")
                    : Optional.empty();
        }
        final var budgetMb = appProperties.getBlocklists().getMaxRedisMb();
        return otherSetsBytes(source) + estimate > budgetMb * MEGABYTE
                ? Optional.of(notLoaded(estimate) + " would take the blocklists past their " + budgetMb
                        + " MB Redis budget (app.blocklists.max-redis-mb)")
                : Optional.empty();
    }

    private long otherSetsBytes(final BlocklistSource source) {
        return blocklistSourceRepository.findAll().stream()
                .filter(other -> !other.getId().equals(source.getId()))
                .map(other -> blocklistStore.memoryBytes(other.getId()))
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    private static String notLoaded(final long estimate) {
        return "Not loaded: the list needs about " + megabytes(estimate) + " MB, which";
    }

    private static long megabytes(final long bytes) {
        return (bytes + MEGABYTE - 1) / MEGABYTE;
    }

}
