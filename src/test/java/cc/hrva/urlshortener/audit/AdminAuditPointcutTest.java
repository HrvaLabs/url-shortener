package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.controller.AdminBlocklistController;
import cc.hrva.urlshortener.dto.BlocklistSourceDto;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.exception.BlocklistSourceNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.UserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuditPointcutTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final String LONG_URL = "https://hagezi-mirror.dnsbunker.org/" + "a".repeat(900) + ".txt";
    private static final BlocklistSourceDto SOURCE = new BlocklistSourceDto("HaGeZi TIF medium", LONG_URL, SourceKind.DOMAINS,
            BlocklistAction.BLOCK, true, 6, List.of(LONG_URL, LONG_URL, LONG_URL));
    private static final String SOURCE_DETAILS = "action: BLOCK, enabled: true, kind: DOMAINS, name: HaGeZi TIF medium, refreshIntervalHours: 6";

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private UserService userService;

    @Mock
    private BlocklistSourceService blocklistSourceService;

    private AdminBlocklistController controller;

    @BeforeEach
    void setUp() {
        lenient().when(userService.getUserFromToken()).thenReturn(User.builder().email(ADMIN).build());
        final var proxyFactory = new AspectJProxyFactory(new AdminBlocklistController(blocklistSourceService));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(new AdminAuditAspect(auditLogService, userService, JsonMapper.builder().build()));
        controller = proxyFactory.getProxy();
    }

    @Test
    void addingABlocklistSourceIsAuditedWithTheCreatedIdAndWhatWasSubmitted() {
        when(blocklistSourceService.create(SOURCE)).thenReturn(new BlocklistSourceResponse(17L, SOURCE.name(), SOURCE.url(), SOURCE.fallbackUrls(),
                SOURCE.kind(), SOURCE.action(), true, 6, null, null, null, null, null, null, null));

        controller.createSource(SOURCE);

        verify(auditLogService).log(ADMIN, "createSource", "Blocklist", "17", SOURCE_DETAILS);
    }

    @Test
    void aSourceUrlThatCarriesAnAccessKeyStaysOutOfTheAuditLog() {
        final var keyed = new BlocklistSourceDto("URLhaus", "https://urlhaus.abuse.ch/downloads/text_online/?auth-key=0123456789abcdef",
                SourceKind.LINKS, BlocklistAction.BLOCK, true, 1, List.of("https://mirror.example/urlhaus.txt?key=fedcba9876543210"));

        controller.updateSource(6L, keyed);

        verify(auditLogService).log(ADMIN, "updateSource", "Blocklist", "6",
                "action: BLOCK, enabled: true, kind: LINKS, name: URLhaus, refreshIntervalHours: 1");
    }

    @Test
    void aRefusedBlocklistSourceIsAuditedAsFailedWithoutATarget() {
        when(blocklistSourceService.create(SOURCE)).thenThrow(new UrlValidationException("Enter a name for the list"));

        assertThatThrownBy(() -> controller.createSource(SOURCE)).isInstanceOf(UrlValidationException.class);

        verify(auditLogService).log(ADMIN, "createSource_FAILED", "Blocklist", "", "Enter a name for the list");
    }

    @Test
    void editingABlocklistSourceIsAuditedWithItsIdAndTheSubmittedFields() {
        controller.updateSource(3L, SOURCE);

        verify(auditLogService).log(ADMIN, "updateSource", "Blocklist", "3", SOURCE_DETAILS);
    }

    @Test
    void removingABlocklistSourceIsAudited() {
        controller.deleteSource(3L);

        verify(blocklistSourceService).delete(3L);
        verify(auditLogService).log(ADMIN, "deleteSource", "Blocklist", "3", "");
    }

    @Test
    void refreshingABlocklistSourceIsAudited() {
        controller.refreshSource(3L);

        verify(auditLogService).log(ADMIN, "refreshSource", "Blocklist", "3", "");
    }

    @Test
    void aFailedBlocklistSourceChangeIsAuditedAsFailed() {
        doThrow(new BlocklistSourceNotFoundException("Blocklist source not found")).when(blocklistSourceService).delete(99L);

        assertThatThrownBy(() -> controller.deleteSource(99L)).isInstanceOf(BlocklistSourceNotFoundException.class);

        verify(auditLogService).log(ADMIN, "deleteSource_FAILED", "Blocklist", "99", "Blocklist source not found");
    }

    @Test
    void listingBlocklistSourcesIsNotAudited() {
        controller.fetchSources();

        verifyNoInteractions(auditLogService);
    }

}
