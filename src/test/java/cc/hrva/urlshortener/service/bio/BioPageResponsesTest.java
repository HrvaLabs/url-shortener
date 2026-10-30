package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BioPageResponsesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T09:00:00Z"), ZoneOffset.UTC);
    private static final User ANA = User.builder().id(5L).active(true).build();

    @Mock
    private BioPageButtonRepository bioPageButtonRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    private BioPageResponses bioPageResponses;
    private BioPage page;
    private CustomDomain domain;

    @BeforeEach
    void setUp() {
        bioPageResponses = new BioPageResponses(bioPageButtonRepository, linkReviewRepository, CLOCK);
        page = BioPage.builder().id(12L).handle("ana").build();
        domain = CustomDomain.builder().id(7L).hostname("go.example.com").status(CustomDomainStatus.ACTIVE).build();
        final var onDomain = Url.builder().id(41L).shortUrl("menu").longUrl("https://cafe.example.org/menu").active(true).visits(0L)
                .owner(ANA).domain(domain).build();
        final var onHrva = Url.builder().id(42L).shortUrl("ana-ig").longUrl("https://instagram.com/ana").active(true).visits(0L).build();
        when(bioPageButtonRepository.findByPageWithUrl(page)).thenReturn(List.of(
                BioPageButton.builder().id(31L).page(page).url(onDomain).title("Menu").icon(BioIcon.WEBSITE).sortOrder(0).visible(true).build(),
                BioPageButton.builder().id(32L).page(page).url(onHrva).title("Instagram").icon(BioIcon.INSTAGRAM).sortOrder(1).visible(true).build()));
        when(linkReviewRepository.findOpenSystemFlaggedUrlIds(anyCollection())).thenReturn(List.of());
    }

    @Test
    void aBioButtonToADomainLinkCarriesTheHostname() {
        final var editor = bioPageResponses.editorView(page);
        final var visitor = bioPageResponses.publicView(page);

        assertThat(editor.buttons()).extracting(BioPageResponse.Button::domain).containsExactly("go.example.com", null);
        assertThat(visitor.buttons()).extracting(PublicBioPageResponse.Button::domain).containsExactly("go.example.com", null);
        final var json = JsonMapper.builder().build();
        assertThat(json.writeValueAsString(visitor.buttons().get(0))).contains("\"domain\":\"go.example.com\"");
        assertThat(json.writeValueAsString(visitor.buttons().get(1))).doesNotContain("domain");
        assertThat(json.writeValueAsString(editor.buttons().get(1))).doesNotContain("domain");
    }

    @Test
    void aButtonToALinkOnATurnedOffDomainIsHiddenFromVisitorsAndMarkedForTheOwner() {
        domain.setStatus(CustomDomainStatus.DISABLED);

        final var editor = bioPageResponses.editorView(page);
        final var visitor = bioPageResponses.publicView(page);

        assertThat(editor.buttons()).extracting(BioPageResponse.Button::linkState).containsExactly(BioLinkState.DOMAIN_OFF, BioLinkState.LIVE);
        assertThat(visitor.buttons()).extracting(PublicBioPageResponse.Button::domain).containsExactly((String) null);
    }

}
