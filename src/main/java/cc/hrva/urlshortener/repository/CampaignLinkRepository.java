package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.CampaignLink;
import cc.hrva.urlshortener.model.CampaignLinkId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignLinkRepository extends JpaRepository<CampaignLink, CampaignLinkId> {

    List<CampaignLink> findByIdCampaignId(long campaignId);

}
