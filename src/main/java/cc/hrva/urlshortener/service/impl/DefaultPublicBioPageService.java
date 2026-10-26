package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.service.PublicBioPageService;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultPublicBioPageService implements PublicBioPageService {

    private static final String NOT_AVAILABLE = "This page isn't available.";

    private final BioPageRepository bioPageRepository;
    private final BioPageResponses bioPageResponses;

    @Override
    public PublicBioPageResponse find(final String handle) {
        return publicPage(handle)
                .map(bioPageResponses::publicView)
                .orElseThrow(() -> new BioPageNotFoundException(NOT_AVAILABLE));
    }

    private Optional<BioPage> publicPage(final String handle) {
        final var normalized = BioHandles.normalize(handle);
        if (BioHandles.formatError(normalized).isPresent()) {
            return Optional.empty();
        }
        return bioPageRepository.findByHandleWithOwner(normalized).filter(BioPage::isPublic);
    }

}
