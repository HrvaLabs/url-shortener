package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.UrlService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkToolsTest {

    private static final User CURRENT_USER = User.builder().id(7L).email("user@localhost").active(true).build();

    @Mock
    private UrlService urlService;

    @Mock
    private McpUserResolver mcpUserResolver;

    private LinkTools linkTools;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getMcp().setIssuer("https://hrva.cc");
        linkTools = new LinkTools(urlService, appProperties, mcpUserResolver);
    }

    @Test
    void listsOnlyCurrentUsersLinksWithFullShortUrls() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(
                link("abc12", "https://example.com/first", 3L, "ACTIVE"),
                link("xyz89", "https://example.org/second", 0L, "EXPIRED")));

        final var result = linkTools.listLinks(null, null, null);

        assertThat(result.lines()).containsExactly(
                "https://hrva.cc/abc12 -> https://example.com/first | 3 visits | ACTIVE",
                "https://hrva.cc/xyz89 -> https://example.org/second | 0 visits | EXPIRED");
        final var order = inOrder(mcpUserResolver, urlService);
        order.verify(mcpUserResolver).currentUser();
        order.verify(urlService).getAllMyUrls(isNull(), any(), any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void filtersByTag() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link("abc12", "https://example.com/first", 1L, "ACTIVE")));

        linkTools.listLinks("example", "work", null);

        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        verify(urlService).getAllMyUrls(isNull(), any(), search.capture());
        assertThat(search.getValue()).isEqualTo(new UrlSearchDto("example", null, null, "work", null, null));
    }

    @Test
    void pageSizeIs20() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page());

        linkTools.listLinks(null, null, null);
        linkTools.listLinks(null, null, 2);

        final var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(urlService, times(2)).getAllMyUrls(isNull(), pageable.capture(), any());
        assertThat(pageable.getAllValues())
                .extracting(Pageable::getPageNumber, Pageable::getPageSize, Pageable::getSort)
                .containsExactly(
                        tuple(0, 20, Sort.by(Sort.Direction.DESC, "createDate")),
                        tuple(2, 20, Sort.by(Sort.Direction.DESC, "createDate")));
    }

    @Test
    void emptyPageSaysSo() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page());

        assertThat(linkTools.listLinks("nothing", null, 5)).isEqualTo("No links found.");
    }

    @Test
    void inactiveUserGetsNoLinks() {
        when(mcpUserResolver.currentUser()).thenThrow(new UserNotFoundException("User is inactive, please contact administrator"));

        assertThatThrownBy(() -> linkTools.listLinks(null, null, null))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(urlService);
    }

    private static Page<UrlResponse> page(final UrlResponse... links) {
        return new PageImpl<>(List.of(links));
    }

    private static UrlResponse link(final String alias, final String destination, final Long visits, final String status) {
        return new UrlResponse(1L, destination, alias, LocalDateTime.now(), null, visits, null, "ACTIVE".equals(status),
                status, Set.of(), false, "user@localhost");
    }

}
