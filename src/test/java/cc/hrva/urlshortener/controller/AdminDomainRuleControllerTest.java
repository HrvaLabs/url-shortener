package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.DomainRuleCreatedResponse;
import cc.hrva.urlshortener.dto.DomainRuleDto;
import cc.hrva.urlshortener.dto.DomainRuleResponse;
import cc.hrva.urlshortener.exception.DomainRuleNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.service.ModerationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminDomainRuleControllerTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final UsernamePasswordAuthenticationToken SIGNED_IN_ADMIN =
            UsernamePasswordAuthenticationToken.authenticated(ADMIN, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    private static final DomainRuleResponse BLOCKED = new DomainRuleResponse(7L, "jofogas.cards", DomainRuleType.BLOCK, "phishing wave", ADMIN,
            LocalDateTime.of(2026, 10, 21, 9, 30));
    private static final DomainRuleResponse TRUSTED = new DomainRuleResponse(8L, "gableci.hrva.cc", DomainRuleType.ALLOW, null, null,
            LocalDateTime.of(2026, 10, 20, 8, 0));

    private MockMvc mockMvc;

    @Mock
    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminDomainRuleController(moderationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listingRulesOfOneTypeReturnsEveryField() throws Exception {
        when(moderationService.domainRules(DomainRuleType.BLOCK)).thenReturn(List.of(BLOCKED));

        mockMvc.perform(get("/api/v1/admin/domain-rules").param("type", "BLOCK").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].domain").value("jofogas.cards"))
                .andExpect(jsonPath("$[0].type").value("BLOCK"))
                .andExpect(jsonPath("$[0].reason").value("phishing wave"))
                .andExpect(jsonPath("$[0].createdBy").value(ADMIN))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    void listingWithoutATypeReturnsEveryRule() throws Exception {
        when(moderationService.domainRules(null)).thenReturn(List.of(BLOCKED, TRUSTED));

        mockMvc.perform(get("/api/v1/admin/domain-rules").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].reason").value(nullValue()))
                .andExpect(jsonPath("$[1].createdBy").value(nullValue()));
    }

    @Test
    void listingAnUnknownRuleTypeIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/domain-rules").param("type", "MAYBE").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for type"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void addingABlockRuleDelegatesAsTheSignedInAdminAndReturnsTheRuleAndTheLinksAffected() throws Exception {
        when(moderationService.createDomainRule(new DomainRuleDto("jofogas.cards", DomainRuleType.BLOCK, "phishing wave"), ADMIN, "admin"))
                .thenReturn(new DomainRuleCreatedResponse(BLOCKED, 12));

        mockMvc.perform(post("/api/v1/admin/domain-rules").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"jofogas.cards\",\"type\":\"BLOCK\",\"reason\":\"phishing wave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule.id").value(7))
                .andExpect(jsonPath("$.rule.domain").value("jofogas.cards"))
                .andExpect(jsonPath("$.rule.type").value("BLOCK"))
                .andExpect(jsonPath("$.linksAffected").value(12));
    }

    @Test
    void addingAnAllowRuleWithoutAReasonReturnsTheRestoredLinks() throws Exception {
        when(moderationService.createDomainRule(new DomainRuleDto("gableci.hrva.cc", DomainRuleType.ALLOW, null), ADMIN, "admin"))
                .thenReturn(new DomainRuleCreatedResponse(TRUSTED, 2));

        mockMvc.perform(post("/api/v1/admin/domain-rules").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"gableci.hrva.cc\",\"type\":\"ALLOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule.type").value("ALLOW"))
                .andExpect(jsonPath("$.linksAffected").value(2));
    }

    @Test
    void anUnknownRuleTypeIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/domain-rules").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"jofogas.cards\",\"type\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(moderationService, never()).createDomainRule(any(), anyString(), anyString());
    }

    @Test
    void aDomainTheModerationServiceRefusesIsABadRequestWithItsMessage() throws Exception {
        when(moderationService.createDomainRule(new DomainRuleDto("com", DomainRuleType.BLOCK, null), ADMIN, "admin"))
                .thenThrow(new UrlValidationException("Enter a domain such as example.com, not a top-level domain or an IP address."));

        mockMvc.perform(post("/api/v1/admin/domain-rules").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"com\",\"type\":\"BLOCK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Enter a domain such as example.com, not a top-level domain or an IP address."));
    }

    @Test
    void removingARuleDelegatesItsIdAsTheSignedInAdmin() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/domain-rules/7").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isNoContent());

        verify(moderationService).removeDomainRule(7L, ADMIN, "admin");
    }

    @Test
    void removingAnUnknownRuleIsNotFound() throws Exception {
        doThrow(new DomainRuleNotFoundException("There is no rule with id 99.")).when(moderationService).removeDomainRule(anyLong(), anyString(), anyString());

        mockMvc.perform(delete("/api/v1/admin/domain-rules/99").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("There is no rule with id 99."));
    }

}
