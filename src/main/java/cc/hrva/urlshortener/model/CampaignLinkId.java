package cc.hrva.urlshortener.model;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public record CampaignLinkId(Long campaignId, Long urlId) implements Serializable {}
