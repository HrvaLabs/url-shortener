package cc.hrva.urlshortener.dto.domain;

import cc.hrva.urlshortener.model.CustomDomainRequest;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import java.time.LocalDateTime;

public record DomainRequestResponse(long id, DomainRequestStatus status, int requestedTotal, String reason, String adminNote, LocalDateTime createdAt,
                                    LocalDateTime reviewedAt) {

    public static DomainRequestResponse of(final CustomDomainRequest request) {
        return new DomainRequestResponse(request.getId(), request.getStatus(), request.getRequestedTotal(), request.getReason(), request.getAdminNote(),
                CustomDomainResponses.seconds(request.getCreatedAt()), CustomDomainResponses.seconds(request.getReviewedAt()));
    }

}
