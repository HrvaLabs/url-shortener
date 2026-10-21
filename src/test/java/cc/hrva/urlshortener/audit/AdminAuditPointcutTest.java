package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.controller.AdminBlocklistController;
import cc.hrva.urlshortener.dto.BlocklistSourceDto;
import cc.hrva.urlshortener.exception.BlocklistSourceNotFoundException;
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

@ExtendWith(MockitoExtension.class)
class AdminAuditPointcutTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final String LONG_URL = "https://hagezi-mirror.dnsbunker.org/" + "a".repeat(900) + ".txt";
    private static final BlocklistSourceDto SOURCE = new BlocklistSourceDto("HaGeZi TIF medium", LONG_URL, SourceKind.DOMAINS,
            BlocklistAction.BLOCK, true, 6, List.of(LONG_URL, LONG_URL, LONG_URL));
    private static final String SOURCE_DETAILS = "action: BLOCK, enabled: true, fallbackUrls: [" + LONG_URL + ", " + LONG_URL + ", " + LONG_URL
            + "], kind: DOMAINS, name: HaGeZi TIF medium, refreshIntervalHours: 6, url: " + LONG_URL;

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
    void addingABlocklistSourceIsAuditedWithWhatWasSubmittedAndNoTargetYet() {
        controller.createSource(SOURCE);

        verify(blocklistSourceService).create(SOURCE);
        verify(auditLogService).log(ADMIN, "createSource", "Blocklist", "", SOURCE_DETAILS);
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
