package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterAnswer;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterProvider;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterReport;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterService;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.util.List;
import java.util.Set;
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminModerationControllerTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final UsernamePasswordAuthenticationToken SIGNED_IN_ADMIN =
            UsernamePasswordAuthenticationToken.authenticated(ADMIN, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    private MockMvc mockMvc;

    @Mock
    private ModerationService moderationService;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private DnsFilterService dnsFilterService;

    @Mock
    private SettingsService settingsService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminModerationController(moderationService, scammerSignalService, linkCheckService,
                        dnsFilterService, settingsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void blockingAUserDelegatesAsTheSignedInAdminWithoutACapAndReturnsTheLinksAffected() throws Exception {
        when(moderationService.blockUser(27L, ADMIN, "admin", null)).thenReturn(4);

        mockMvc.perform(post("/api/v1/admin/users/27/block").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linksAffected").value(4));
    }

    @Test
    void unblockingAUserReactivatesItAndReturnsTheRestoredLinks() throws Exception {
        when(moderationService.reactivateUser(27L, ADMIN, "admin")).thenReturn(3);

        mockMvc.perform(post("/api/v1/admin/users/27/unblock").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linksAffected").value(3));

        verify(moderationService).reactivateUser(27L, ADMIN, "admin");
    }

    @Test
    void blockingAnUnknownUserIsNotFound() throws Exception {
        when(moderationService.blockUser(99L, ADMIN, "admin", null)).thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(post("/api/v1/admin/users/99/block").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User not found"));
    }

    @Test
    void blockingAnAdminAccountIsABadRequest() throws Exception {
        when(moderationService.blockUser(1L, ADMIN, "admin", null)).thenThrow(new UrlValidationException("Admin accounts can't be blocked here."));

        mockMvc.perform(post("/api/v1/admin/users/1/block").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Admin accounts can't be blocked here."));
    }

    @Test
    void possibleScammersAreListedByUserIdInOrder() throws Exception {
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(41L, 27L, 3L));

        mockMvc.perform(get("/api/v1/admin/possible-scammers").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(contains(3, 27, 41)));
    }

    @Test
    void checkingAUrlExplainsEveryMatchWithTheHostAndRegistrableDomain() throws Exception {
        when(linkCheckService.explain("https://login.jofogas.cards/item/7")).thenReturn(new LinkVerdict(Outcome.BLOCK,
                List.of("on list HaGeZi TIF medium", "domain registered in the last 30 days"), BlockReason.BLOCKLIST, "HaGeZi TIF medium"));

        mockMvc.perform(post("/api/v1/admin/url-check").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\" https://login.jofogas.cards/item/7 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("BLOCK"))
                .andExpect(jsonPath("$.reasons").value(contains("on list HaGeZi TIF medium", "domain registered in the last 30 days")))
                .andExpect(jsonPath("$.blockReason").value("BLOCKLIST"))
                .andExpect(jsonPath("$.blockDetail").value("HaGeZi TIF medium"))
                .andExpect(jsonPath("$.host").value("login.jofogas.cards"))
                .andExpect(jsonPath("$.registrableDomain").value("jofogas.cards"));

        verify(linkCheckService, never()).check(any());
    }

    @Test
    void checkingACleanUrlHasNoBlockReason() throws Exception {
        when(linkCheckService.explain("https://gableci.hrva.cc/menu")).thenReturn(LinkVerdict.clean());

        mockMvc.perform(post("/api/v1/admin/url-check").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://gableci.hrva.cc/menu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CLEAN"))
                .andExpect(jsonPath("$.reasons").isEmpty())
                .andExpect(jsonPath("$.blockReason").value(nullValue()))
                .andExpect(jsonPath("$.blockDetail").value(nullValue()))
                .andExpect(jsonPath("$.host").value("gableci.hrva.cc"))
                .andExpect(jsonPath("$.registrableDomain").value("hrva.cc"));
    }

    @Test
    void checkingAUrlWithoutAReadableHostLeavesTheHostAndRegistrableDomainEmpty() throws Exception {
        when(linkCheckService.explain("https://exa mple.com/")).thenReturn(LinkVerdict.clean());

        mockMvc.perform(post("/api/v1/admin/url-check").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://exa mple.com/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value(nullValue()))
                .andExpect(jsonPath("$.registrableDomain").value(nullValue()));
    }

    @Test
    void checkingAUrlListsWhatEachDnsFilterAnswered() throws Exception {
        when(linkCheckService.explain("https://malware.wicar.org/data/eicar.com")).thenReturn(LinkVerdict.clean());
        when(settingsService.isOn(SettingKey.DNS_FILTER_CHECK)).thenReturn(true);
        when(dnsFilterService.lookup("malware.wicar.org")).thenReturn(new DnsFilterReport("malware.wicar.org", true, List.of(
                new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.BLOCKED, "NXDOMAIN"),
                new DnsFilterAnswer(DnsFilterProvider.CLOUDFLARE, DnsFilterStatus.BLOCKED, "0.0.0.0"),
                new DnsFilterAnswer(DnsFilterProvider.CLEANBROWSING, DnsFilterStatus.NOT_BLOCKED, "208.94.116.246"),
                new DnsFilterAnswer(DnsFilterProvider.CONTROL_D, DnsFilterStatus.NO_ANSWER, null))));

        mockMvc.perform(post("/api/v1/admin/url-check").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://malware.wicar.org/data/eicar.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dnsFilterHostExists").value(true))
                .andExpect(jsonPath("$.dnsFilters.length()").value(4))
                .andExpect(jsonPath("$.dnsFilters[0].filter").value("Quad9"))
                .andExpect(jsonPath("$.dnsFilters[0].status").value("BLOCKED"))
                .andExpect(jsonPath("$.dnsFilters[0].answer").value("NXDOMAIN"))
                .andExpect(jsonPath("$.dnsFilters[3].filter").value("Control D"))
                .andExpect(jsonPath("$.dnsFilters[3].status").value("NO_ANSWER"));
    }

    @Test
    void checkingAUrlWithTheDnsFilterCheckOffAsksNoFilter() throws Exception {
        when(linkCheckService.explain("https://gableci.hrva.cc/menu")).thenReturn(LinkVerdict.clean());
        when(settingsService.isOn(SettingKey.DNS_FILTER_CHECK)).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/url-check").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://gableci.hrva.cc/menu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dnsFilters").isEmpty())
                .andExpect(jsonPath("$.dnsFilterHostExists").value(false));

        verifyNoInteractions(dnsFilterService);
    }

    @Test
    void checkingAnIpAddressAsksNoFilter() throws Exception {
        when(linkCheckService.explain("http://203.0.113.5/login")).thenReturn(LinkVerdict.clean());
        when(settingsService.isOn(SettingKey.DNS_FILTER_CHECK)).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/url-check").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://203.0.113.5/login\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dnsFilters").isEmpty());

        verifyNoInteractions(dnsFilterService);
    }

    @Test
    void checkingABlankUrlIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/url-check").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter a URL to check"));

        verifyNoInteractions(linkCheckService);
    }

}
