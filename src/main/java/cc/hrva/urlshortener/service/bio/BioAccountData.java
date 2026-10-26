package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.dto.DataExportDto;
import cc.hrva.urlshortener.model.BioHandleHold;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BioAccountData {

    private final BioPageRepository bioPageRepository;
    private final BioPageLock bioPageLock;
    private final BioPageButtonRepository bioPageButtonRepository;
    private final BioHandleHoldRepository bioHandleHoldRepository;
    private final Clock clock;

    public Optional<DataExportDto.BioPageExport> exportFor(final User user) {
        return bioPageRepository.findByOwner(user)
                .map(page -> DataExportDto.BioPageExport.from(page, bioPageButtonRepository.findByPageWithUrl(page)));
    }

    public void deleteFor(final User user) {
        bioPageRepository.findByOwner(user)
                .flatMap(bioPageLock::lock)
                .ifPresent(this::holdHandleAndDelete);
    }

    private void holdHandleAndDelete(final BioPage page) {
        if (page.getHandle() != null) {
            final var now = LocalDateTime.now(clock);
            bioHandleHoldRepository.save(BioHandleHold.builder()
                    .handle(page.getHandle())
                    .heldUntil(now.plus(BioHandles.HOLD))
                    .createdAt(now)
                    .build());
        }
        bioPageRepository.delete(page);
        log.info("Bio page deleted with its account pageId={}", page.getId());
    }

}
