package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlocklistMemoryGuardTest {

    private static final long MEGABYTE = 1024L * 1024L;

    @Mock
    private BlocklistStore blocklistStore;

    @Mock
    private BlocklistSourceRepository blocklistSourceRepository;

    @Test
    void aTopSitesListIsChargedThePerDomainCost() {
        final var tranco = BlocklistSource.builder().id(10L).name("Tranco top sites").kind(SourceKind.TOP_SITES).action(BlocklistAction.TRUST).build();
        when(blocklistStore.memory()).thenReturn(new BlocklistStore.RedisMemory(1000 * MEGABYTE, 1000 * MEGABYTE));

        assertThat(new BlocklistMemoryGuard(blocklistStore, blocklistSourceRepository, new AppProperties()).refusal(tranco, 20_000))
                .contains("Not loaded: the list needs about 2 MB, which would take Redis past 80 % of its 1000 MB maxmemory");
    }

}
