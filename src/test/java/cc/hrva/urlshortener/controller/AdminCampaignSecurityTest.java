package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignDetailResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignDomainResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignLinkResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignResponse;
import cc.hrva.urlshortener.dto.campaign.ReportStatus;
import cc.hrva.urlshortener.exception.CampaignNotFoundException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CampaignStatus;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminCampaignSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class AdminCampaignSecurityTest {

    private static final String CAMPAIGNS = "/api/v1/admin/campaigns";
    private static final String DETAIL = CAMPAIGNS + "/4";
    private static final String LINKS = CAMPAIGNS + "/4/links";
    private static final String ALLOW = CAMPAIGNS + "/4/allow";
    private static final String BLOCK_PREVIEW = CAMPAIGNS + "/4/block-preview";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final LocalDateTime FIRST_SEEN = LocalDateTime.of(2026, 10, 20, 8, 15);
    private static final LocalDateTime LAST_SEEN = LocalDateTime.of(2026, 10, 29, 9, 30);
    private static final LocalDateTime HELD_UNTIL = LocalDateTime.of(2026, 11, 5, 9, 30);
    private static final CampaignResponse CAMPAIGN = new CampaignResponse(4L, "/wqamhcsom?r&t", "/wqamhcsom", List.of("r", "t"),
            CampaignStatus.HOLDING, true, null, FIRST_SEEN, LAST_SEEN, HELD_UNTIL, null, null, new CampaignResponse.Links(62, 0, 58, 4), 39, 970,
            new CampaignResponse.Reports(39, 12));
    private static final String CAMPAIGN_JSON = """
            {"id":4,"shape":"/wqamhcsom?r&t","path":"/wqamhcsom","queryNames":["r","t"],"status":"HOLDING","holding":true,"holdingOff":null,
             "firstSeenAt":"2026-10-20T08:15:00","lastSeenAt":"2026-10-29T09:30:00","activeUntil":"2026-11-05T09:30:00",
             "allowedBy":null,"allowedAt":null,"links":{"total":62,"active":0,"held":58,"blocked":4},"domains":39,"clicks":970,
             "reports":{"reported":39,"suspended":12}}""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampaignService campaignService;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        signedIn("admin-token", ADMIN, "ROLE_ADMIN");
        signedIn("user-token", USER, "ROLE_USER");
    }

    @Test
    void anAdminListsTheCampaignsAsAPageOfTheContractsRows() throws Exception {
        when(campaignService.campaigns(isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(CAMPAIGN), PageRequest.of(0, 20), 1));

        mockMvc.perform(signedIn(get(CAMPAIGNS), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[" + CAMPAIGN_JSON + "],\"page\":{\"size\":20,\"number\":0,\"totalElements\":1,\"totalPages\":1}}",
                        JsonCompareMode.STRICT));

        verify(campaignService).campaigns(null, PageRequest.of(0, 20));
    }

    @Test
    void theListPassesTheSearchAndTheSortAndHoldsAtMost100Campaigns() throws Exception {
        when(campaignService.campaigns(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 100), 0));

        mockMvc.perform(signedIn(get(CAMPAIGNS + "?page=1&size=500&sort=status,asc&search=wqam"), "admin-token"))
                .andExpect(status().isOk());

        verify(campaignService).campaigns("wqam", PageRequest.of(1, 100, Sort.by(Sort.Order.asc("status"))));
    }

    @Test
    void anAdminGetsACampaignWithItsDomainsAndNoLinks() throws Exception {
        final var report = new ReportStatus(LocalDateTime.of(2026, 10, 28, 7, 0), "karlo@hrva.cc", "NameSilo, LLC", LocalDateTime.of(2026, 10, 29, 2, 0));
        when(campaignService.detail(4L)).thenReturn(new CampaignDetailResponse(CAMPAIGN,
                List.of(new CampaignDomainResponse("omaehhm.com", 2, report), new CampaignDomainResponse("c.com", 1, null))));

        mockMvc.perform(signedIn(get(DETAIL), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"campaign\":" + CAMPAIGN_JSON + ",\"domains\":["
                        + "{\"domain\":\"omaehhm.com\",\"links\":2,\"report\":{\"reportedAt\":\"2026-10-28T07:00:00\",\"reportedBy\":\"karlo@hrva.cc\","
                        + "\"registrar\":\"NameSilo, LLC\",\"suspendedAt\":\"2026-10-29T02:00:00\"}},"
                        + "{\"domain\":\"c.com\",\"links\":1,\"report\":null}]}", JsonCompareMode.STRICT));
    }

    @Test
    void anAdminGetsACampaignsLinksAsAPageOfAtMost100() throws Exception {
        final var link = new CampaignLinkResponse(41L, "qf9qb", "https://ll.omaehhm.com/wqamhcsom/?r=plfN8D&t=1793210826", "omaehhm.com",
                LocalDateTime.of(2026, 10, 21, 10, 5), false, BlockReason.CAMPAIGN, 123);
        when(campaignService.links(eq(4L), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(link), PageRequest.of(0, 20), 62));

        mockMvc.perform(signedIn(get(LINKS), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[{\"id\":41,\"shortUrl\":\"qf9qb\",\"longUrl\":\"https://ll.omaehhm.com/wqamhcsom/?r=plfN8D&t=1793210826\","
                        + "\"domain\":\"omaehhm.com\",\"createDate\":\"2026-10-21T10:05:00\",\"active\":false,\"blockReason\":\"CAMPAIGN\",\"clicks\":123}],"
                        + "\"page\":{\"size\":20,\"number\":0,\"totalElements\":62,\"totalPages\":4}}", JsonCompareMode.STRICT));
        mockMvc.perform(signedIn(get(LINKS + "?page=2&size=101&sort=createDate,asc&search=qf9"), "admin-token"))
                .andExpect(status().isOk());

        verify(campaignService).links(4L, null, PageRequest.of(0, 20));
        verify(campaignService).links(4L, "qf9", PageRequest.of(2, 100, Sort.by(Sort.Order.asc("createDate"))));
    }

    @Test
    void anAdminAllowsAShapeAsThemselves() throws Exception {
        when(campaignService.allow(4L, ADMIN)).thenReturn(2);

        mockMvc.perform(signedIn(post(ALLOW), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"linksAffected\":2}", JsonCompareMode.STRICT));
    }

    @Test
    void anAdminPreviewsBlockingEveryDomainOfACampaign() throws Exception {
        when(campaignService.blockPreview(4L)).thenReturn(List.of(new DomainBlockPreviewResponse("omaehhm.com", List.of("ll.omaehhm.com"), 2, 0, 2, null)));

        mockMvc.perform(signedIn(post(BLOCK_PREVIEW), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"domain\":\"omaehhm.com\",\"hosts\":[\"ll.omaehhm.com\"],\"selectedLinks\":2,\"activeLinks\":0,"
                        + "\"heldLinks\":2,\"rule\":null}]", JsonCompareMode.STRICT));
    }

    @Test
    void anUnknownCampaignIsNotFound() throws Exception {
        final var notFound = new CampaignNotFoundException("There is no campaign 99.");
        when(campaignService.detail(99L)).thenThrow(notFound);
        when(campaignService.links(eq(99L), any(), any(Pageable.class))).thenThrow(notFound);
        when(campaignService.allow(99L, ADMIN)).thenThrow(notFound);
        when(campaignService.blockPreview(99L)).thenThrow(notFound);

        for (final var request : List.of(get(CAMPAIGNS + "/99"), get(CAMPAIGNS + "/99/links"), post(CAMPAIGNS + "/99/allow"),
                post(CAMPAIGNS + "/99/block-preview"))) {
            mockMvc.perform(signedIn(request, "admin-token"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().json("{\"error\":\"There is no campaign 99.\"}", JsonCompareMode.STRICT));
        }
    }

    @Test
    void aSignedInUserWhoIsNotAnAdminIsForbidden() throws Exception {
        for (final var request : everyEndpoint()) {
            mockMvc.perform(signedIn(request, "user-token"))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(campaignService);
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        for (final var request : everyEndpoint()) {
            mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }

        verifyNoInteractions(campaignService);
    }

    private static List<MockHttpServletRequestBuilder> everyEndpoint() {
        return List.of(get(CAMPAIGNS), get(DETAIL), get(LINKS), post(ALLOW), post(BLOCK_PREVIEW));
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
    }

    private static MockHttpServletRequestBuilder signedIn(final MockHttpServletRequestBuilder request, final String token) {
        return request.header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            AdminCampaignController.class
    })
    static class TestApplication {
    }

}
