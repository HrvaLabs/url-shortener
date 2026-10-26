package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BioPageResponses {

    private final BioPageButtonRepository bioPageButtonRepository;
    private final LinkReviewRepository linkReviewRepository;
    private final Clock clock;

    public List<BioPageButton> buttonsOf(final BioPage page) {
        return bioPageButtonRepository.findByPageWithUrl(page);
    }

    public Map<Long, BioLinkState> statesOf(final Collection<Url> urls) {
        if (urls.isEmpty()) {
            return Map.of();
        }
        final var inReview = Set.copyOf(linkReviewRepository.findOpenSystemFlaggedUrlIds(urls.stream().map(Url::getId).toList()));
        final var now = LocalDateTime.now(clock);
        return urls.stream().collect(Collectors.toMap(
                Url::getId,
                url -> BioLinkStates.of(url, inReview.contains(url.getId()), now),
                (first, second) -> first));
    }

    public BioPageResponse editorView(final BioPage page) {
        return editorView(page, buttonsOf(page));
    }

    public BioPageResponse editorView(final BioPage page, final List<BioPageButton> buttons) {
        final var states = statesOf(buttons.stream().map(BioPageButton::getUrl).toList());
        return BioPageResponse.from(page, buttons.stream()
                .map(button -> BioPageResponse.Button.from(button, states.get(button.getUrl().getId())))
                .toList());
    }

}
