package cc.hrva.urlshortener.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
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
@Table(name = "campaign_link")
public class CampaignLink {

    @EmbeddedId
    private CampaignLinkId id;
    private String domain;
    private LocalDateTime createdAt;

}
