package cc.hrva.urlshortener.controller;

import java.util.Collections;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.ApiKeyUpdateDto;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.service.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ApiKeyControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ApiKeyService apiKeyService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new ApiKeyController(apiKeyService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldGenerateNewApiKey() throws Exception {
        final var apiKey = ApiKey.builder().id(1L).key("new-key").build();
        when(apiKeyService.generateNewApiKey()).thenReturn(ApiKeyResponse.from(apiKey));

        mockMvc.perform(post("/api/v1/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("new-key"));
    }

    @Test
    void shouldFetchMyApiKeys() throws Exception {
        final var apiKey = ApiKey.builder().id(1L).build();
        final var apiKeyResponses = Collections.singletonList(ApiKeyResponse.from(apiKey));
        when(apiKeyService.fetchMyApiKeys()).thenReturn(apiKeyResponses);

        mockMvc.perform(get("/api/v1/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1));
    }

    @Test
    void shouldFetchAllApiKeys() throws Exception {
        final var apiKey = ApiKey.builder().id(1L).build();
        final var apiKeyResponse = ApiKeyResponse.from(apiKey);
        final var apiKeyList = Collections.singletonList(apiKeyResponse);
        final var pageable = PageRequest.of(0, 20);
        final var apiKeys = new PageImpl<>(apiKeyList, pageable, apiKeyList.size());
        when(apiKeyService.fetchAllApiKeys(any(Pageable.class))).thenReturn(apiKeys);

        mockMvc.perform(get("/api/v1/api-keys/all").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.size()").value(1));
    }

    @Test
    void shouldUpdateApiKey() throws Exception {
        final var apiKey = ApiKey.builder().id(1L).key("updated-key").build();
        when(apiKeyService.updateKey(any(ApiKeyUpdateDto.class))).thenReturn(ApiKeyResponse.from(apiKey));

        mockMvc.perform(put("/api/v1/api-keys/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ApiKeyUpdateDto.builder().id(1L).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void shouldRevokeApiKey() throws Exception {
        final var apiKey = ApiKey.builder().id(1L).active(false).build();
        when(apiKeyService.revokeApiKey(1L)).thenReturn(ApiKeyResponse.from(apiKey));

        mockMvc.perform(patch("/api/v1/api-keys/1/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void deletingAKeyAnswersNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/1"))
                .andExpect(status().isNoContent());

        verify(apiKeyService).deleteApiKey(1L);
    }

    @Test
    void deletingTheInactiveKeysAnswersHowManyWereDeleted() throws Exception {
        when(apiKeyService.deleteMyInactiveApiKeys()).thenReturn(3);

        mockMvc.perform(delete("/api/v1/api-keys/inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));
    }

}
