package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.dto.ReviewBulkResolveResponse;
import cc.hrva.urlshortener.dto.ReviewEntryResponse;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.enums.ReviewAction;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminReviewQueueControllerTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final String BULK_RESOLVE = "/api/v1/admin/review-queue/resolve";
    private static final UsernamePasswordAuthenticationToken SIGNED_IN_ADMIN =
            UsernamePasswordAuthenticationToken.authenticated(ADMIN, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    private static final ReviewEntryResponse ENTRY = new ReviewEntryResponse(287L, "v0DB3", "https://findfavoritedate.life/?u=f6yp608",
            "findfavoritedate.life", "findfavoritedate.life", 27L,
            "rhea@gmail.com", LocalDateTime.of(2026, 10, 19, 12, 0), true, LocalDateTime.of(2026, 10, 20, 7, 15),
            List.of("domain registered in the last 30 days", "high-risk domain ending .life"), LocalDateTime.of(2026, 10, 20, 7, 16));
    private static final ReviewEntryResponse ANONYMOUS_ENTRY = new ReviewEntryResponse(288L, "njuskaio_verify", "https://njuskaio.cam/a/MAUB3",
            "njuskaio.cam", "njuskaio.cam", null, null, null, false, LocalDateTime.of(2026, 10, 20, 6, 0), List.of("imitates njuskalo"), LocalDateTime.of(2026, 10, 20, 6, 1));

    private MockMvc mockMvc;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        final var jsonMapper = JsonMapper.builder();
        new JacksonConfiguration().enumsByNameOnly().customize(jsonMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminReviewQueueController(reviewQueueService, moderationService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void theQueueListsOpenEntriesWithEveryContractField() throws Exception {
        when(reviewQueueService.openEntries(any(Pageable.class), isNull(), isNull()))
                .thenReturn(new PageImpl<>(List.of(ENTRY, ANONYMOUS_ENTRY), PageRequest.of(0, 20), 2));

        mockMvc.perform(get("/api/v1/admin/review-queue").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].urlId").value(287))
                .andExpect(jsonPath("$.content[0].shortUrl").value("v0DB3"))
                .andExpect(jsonPath("$.content[0].longUrl").value("https://findfavoritedate.life/?u=f6yp608"))
                .andExpect(jsonPath("$.content[0].host").value("findfavoritedate.life"))
                .andExpect(jsonPath("$.content[0].registrableDomain").value("findfavoritedate.life"))
                .andExpect(jsonPath("$.content[0].ownerId").value(27))
                .andExpect(jsonPath("$.content[0].ownerEmail").value("rhea@gmail.com"))
                .andExpect(jsonPath("$.content[0].ownerCreatedAt").exists())
                .andExpect(jsonPath("$.content[0].possibleScammer").value(true))
                .andExpect(jsonPath("$.content[0].createDate").exists())
                .andExpect(jsonPath("$.content[0].reasons").value(contains("domain registered in the last 30 days", "high-risk domain ending .life")))
                .andExpect(jsonPath("$.content[0].flaggedAt").exists())
                .andExpect(jsonPath("$.content[1].ownerId").value(nullValue()))
                .andExpect(jsonPath("$.content[1].ownerEmail").value(nullValue()))
                .andExpect(jsonPath("$.content[1].ownerCreatedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[1].possibleScammer").value(false))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void theQueueFiltersByAReasonSubstringAndPassesThePage() throws Exception {
        final var pageable = ArgumentCaptor.forClass(Pageable.class);
        when(reviewQueueService.openEntries(pageable.capture(), eq("young"), isNull())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 1), 2));

        mockMvc.perform(get("/api/v1/admin/review-queue").param("page", "2").param("size", "1").param("reason", "young").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk());

        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void theQueueFiltersByLinkAge() throws Exception {
        when(reviewQueueService.openEntries(any(Pageable.class), isNull(), eq(7))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/admin/review-queue").param("olderThanDays", "7").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isOk());

        verify(reviewQueueService).openEntries(any(Pageable.class), isNull(), eq(7));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLinkAgeThatIsNotANumberIsABadRequestWithoutAStackTrace(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/admin/review-queue").param("olderThanDays", "abc").principal(SIGNED_IN_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for olderThanDays"));

        verifyNoInteractions(reviewQueueService);
        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void resolvingAnEntryDelegatesTheActionAndDomainAsTheSignedInAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/review-queue/287/resolve").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"BLOCK_DOMAIN\",\"domain\":\"findfavoritedate.life\"}"))
                .andExpect(status().isNoContent());

        verify(moderationService).resolveReview(287L, ReviewAction.BLOCK_DOMAIN, "findfavoritedate.life", ADMIN, "admin");
    }

    @Test
    void resolvingWithoutADomainPassesNone() throws Exception {
        mockMvc.perform(post("/api/v1/admin/review-queue/287/resolve").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"OK\"}"))
                .andExpect(status().isNoContent());

        verify(moderationService).resolveReview(287L, ReviewAction.OK, null, ADMIN, "admin");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anUnknownActionIsABadRequestListingTheActionsWithoutAStackTrace(final CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/v1/admin/review-queue/287/resolve").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DELETE_EVERYTHING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"));

        verifyNoInteractions(moderationService);
        assertThat(output).doesNotContain("DELETE_EVERYTHING").doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void aMissingActionIsABadRequestNamingTheField() throws Exception {
        mockMvc.perform(post("/api/v1/admin/review-queue/287/resolve").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.action").value("Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void anActionInTheWrongCaseIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/review-queue/287/resolve").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void aNumberIsNotAnAction() throws Exception {
        mockMvc.perform(post("/api/v1/admin/review-queue/287/resolve").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void resolvingAnUnknownLinkIsNotFound() throws Exception {
        doThrow(new UrlNotFoundException("Url doesn't exist")).when(moderationService).resolveReview(404L, ReviewAction.OK, null, ADMIN, "admin");

        mockMvc.perform(post("/api/v1/admin/review-queue/404/resolve").principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"OK\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Url doesn't exist"));
    }

    @Test
    void resolvingSeveralEntriesDelegatesTheIdsAndActionAsTheSignedInAdminAndListsWhatFailed() throws Exception {
        when(moderationService.resolveReviews(List.of(287L, 288L, 404L), ReviewAction.BLOCK_DOMAIN, ADMIN, "admin"))
                .thenReturn(new ReviewBulkResolveResponse(List.of(287L, 288L), List.of(new ReviewBulkResolveResponse.Failure(404L, "Url doesn't exist"))));

        mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlIds\":[287,288,404],\"action\":\"BLOCK_DOMAIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(contains(287, 288)))
                .andExpect(jsonPath("$.failed[0].urlId").value(404))
                .andExpect(jsonPath("$.failed[0].error").value("Url doesn't exist"));
    }

    @Test
    void aHundredEntriesCanBeResolvedAtOnce() throws Exception {
        final var hundred = LongStream.rangeClosed(1, 100).boxed().toList();
        when(moderationService.resolveReviews(hundred, ReviewAction.OK, ADMIN, "admin")).thenReturn(new ReviewBulkResolveResponse(hundred, List.of()));

        mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody(hundred, "OK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved.length()").value(100))
                .andExpect(jsonPath("$.failed").isEmpty());
    }

    @Test
    void moreThanAHundredEntriesAreABadRequest() throws Exception {
        mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody(LongStream.rangeClosed(1, 101).boxed().toList(), "OK")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.urlIds").value("Select between 1 and 100 links"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void noEntriesAreABadRequest() throws Exception {
        for (final var body : List.of("{\"urlIds\":[],\"action\":\"OK\"}", "{\"action\":\"OK\"}")) {
            mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.urlIds").value("Select between 1 and 100 links"));
        }

        verifyNoInteractions(moderationService);
    }

    @Test
    void theSameEntryTwiceIsABadRequest() throws Exception {
        mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlIds\":[287,288,287],\"action\":\"OK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.urlIds").value("Select each link only once"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void anEmptyIdIsABadRequest() throws Exception {
        mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlIds\":[287,null],\"action\":\"OK\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(moderationService);
    }

    @Test
    void anUnknownBulkActionIsABadRequestListingTheActions() throws Exception {
        mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlIds\":[287],\"action\":\"DELETE_EVERYTHING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"));

        verifyNoInteractions(moderationService);
    }

    @Test
    void aMissingBulkActionIsABadRequest() throws Exception {
        mockMvc.perform(post(BULK_RESOLVE).principal(SIGNED_IN_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlIds\":[287]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.action").value("Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER"));

        verifyNoInteractions(moderationService);
    }

    private static String bulkBody(final List<Long> urlIds, final String action) {
        return "{\"urlIds\":" + urlIds + ",\"action\":\"" + action + "\"}";
    }

}
