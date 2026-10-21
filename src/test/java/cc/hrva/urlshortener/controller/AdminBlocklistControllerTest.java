package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.BlocklistSourceDto;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.exception.BlocklistSourceNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminBlocklistControllerTest {

    private static final String SOURCE_JSON = "{\"name\":\"HaGeZi TIF medium\",\"url\":\"https://hagezi-mirror.dnsbunker.org/tif.medium.txt\","
            + "\"kind\":\"DOMAINS\",\"action\":\"BLOCK\",\"enabled\":true,\"refreshIntervalHours\":6,"
            + "\"fallbackUrls\":[\"https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.medium.txt\"]}";
    private static final BlocklistSourceDto SOURCE = new BlocklistSourceDto("HaGeZi TIF medium", "https://hagezi-mirror.dnsbunker.org/tif.medium.txt",
            SourceKind.DOMAINS, BlocklistAction.BLOCK, true, 6, List.of("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.medium.txt"));
    private static final BlocklistSourceResponse RESPONSE = new BlocklistSourceResponse(3L, "HaGeZi TIF medium",
            "https://hagezi-mirror.dnsbunker.org/tif.medium.txt", List.of("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.medium.txt"),
            SourceKind.DOMAINS, BlocklistAction.BLOCK, true, 6, LocalDateTime.of(2026, 10, 21, 4, 0), LocalDateTime.of(2026, 10, 21, 4, 0),
            "https://hagezi-mirror.dnsbunker.org/tif.medium.txt", 606_000L, "2026.1021.0400.12", null, 60_600_000L);

    private MockMvc mockMvc;

    @Mock
    private BlocklistSourceService blocklistSourceService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminBlocklistController(blocklistSourceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listingSourcesReturnsEveryContractField() throws Exception {
        when(blocklistSourceService.list()).thenReturn(List.of(RESPONSE));

        mockMvc.perform(get("/api/v1/admin/blocklist-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].name").value("HaGeZi TIF medium"))
                .andExpect(jsonPath("$[0].url").value("https://hagezi-mirror.dnsbunker.org/tif.medium.txt"))
                .andExpect(jsonPath("$[0].fallbackUrls").value(contains("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.medium.txt")))
                .andExpect(jsonPath("$[0].kind").value("DOMAINS"))
                .andExpect(jsonPath("$[0].action").value("BLOCK"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].refreshIntervalHours").value(6))
                .andExpect(jsonPath("$[0].lastFetchedAt").exists())
                .andExpect(jsonPath("$[0].lastSuccessAt").exists())
                .andExpect(jsonPath("$[0].lastSuccessUrl").value("https://hagezi-mirror.dnsbunker.org/tif.medium.txt"))
                .andExpect(jsonPath("$[0].domainCount").value(606_000))
                .andExpect(jsonPath("$[0].listVersion").value("2026.1021.0400.12"))
                .andExpect(jsonPath("$[0].lastError").value(nullValue()))
                .andExpect(jsonPath("$[0].memoryBytes").value(60_600_000));
    }

    @Test
    void addingASourcePassesTheInputAndReturnsTheSource() throws Exception {
        when(blocklistSourceService.create(SOURCE)).thenReturn(RESPONSE);

        mockMvc.perform(post("/api/v1/admin/blocklist-sources").contentType(MediaType.APPLICATION_JSON).content(SOURCE_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3));
    }

    @Test
    void editingASourcePassesItsIdAndTheInput() throws Exception {
        when(blocklistSourceService.update(3L, SOURCE)).thenReturn(RESPONSE);

        mockMvc.perform(put("/api/v1/admin/blocklist-sources/3").contentType(MediaType.APPLICATION_JSON).content(SOURCE_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HaGeZi TIF medium"));
    }

    @Test
    void removingASourceAnswersNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/blocklist-sources/3"))
                .andExpect(status().isNoContent());

        verify(blocklistSourceService).delete(3L);
    }

    @Test
    void refreshingASourceReturnsTheRefreshedSource() throws Exception {
        when(blocklistSourceService.refresh(3L)).thenReturn(RESPONSE);

        mockMvc.perform(post("/api/v1/admin/blocklist-sources/3/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listVersion").value("2026.1021.0400.12"));
    }

    @Test
    void anUnknownSourceIsNotFound() throws Exception {
        doThrow(new BlocklistSourceNotFoundException("Blocklist source not found")).when(blocklistSourceService).delete(99L);

        mockMvc.perform(delete("/api/v1/admin/blocklist-sources/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Blocklist source not found"));
    }

    @Test
    void anInvalidSourceIsABadRequestWithTheServiceMessage() throws Exception {
        when(blocklistSourceService.create(SOURCE)).thenThrow(new UrlValidationException("URL must be a valid https:// address"));

        mockMvc.perform(post("/api/v1/admin/blocklist-sources").contentType(MediaType.APPLICATION_JSON).content(SOURCE_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("URL must be a valid https:// address"));
    }

}
