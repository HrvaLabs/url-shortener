package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.CampaignStatus;
import cc.hrva.urlshortener.service.campaign.UrlShape;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "campaign")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String shapeKey;
    private String path;
    @Convert(converter = QueryNamesConverter.class)
    private List<String> queryNames;
    @Enumerated(EnumType.STRING)
    private CampaignStatus status;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime activeUntil;
    private LocalDateTime alertedAt;
    private String allowedBy;
    private LocalDateTime allowedAt;

    public UrlShape shape() {
        return new UrlShape(path, queryNames);
    }

    public boolean isActiveAt(final LocalDateTime now) {
        return activeUntil.isAfter(now);
    }

    public boolean isHoldingAt(final LocalDateTime now) {
        return status == CampaignStatus.HOLDING && isActiveAt(now);
    }

}
