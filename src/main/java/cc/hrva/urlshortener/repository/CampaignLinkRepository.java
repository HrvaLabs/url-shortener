package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.dto.campaign.CampaignDomainCount;
import cc.hrva.urlshortener.dto.campaign.CampaignLinkCounts;
import cc.hrva.urlshortener.dto.campaign.CampaignReportCounts;
import cc.hrva.urlshortener.model.CampaignLink;
import cc.hrva.urlshortener.model.CampaignLinkId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignLinkRepository extends JpaRepository<CampaignLink, CampaignLinkId> {

    List<CampaignLink> findByIdCampaignId(long campaignId);

    List<CampaignLink> findByIdCampaignIdIn(Collection<Long> campaignIds);

    List<CampaignLink> findByIdCampaignIdAndIdUrlIdIn(long campaignId, Collection<Long> urlIds);

    @Query("""
            select new cc.hrva.urlshortener.dto.campaign.CampaignLinkCounts(cl.id.campaignId, count(cl),
                sum(case when u.active = true then 1 else 0 end),
                sum(case when u.active = false and u.blockReason = cc.hrva.urlshortener.model.enums.BlockReason.CAMPAIGN then 1 else 0 end),
                sum(case when u.active = false
                         and (u.blockReason <> cc.hrva.urlshortener.model.enums.BlockReason.CAMPAIGN
                              or (u.blockReason is null and u.threatType is not null)) then 1 else 0 end),
                count(distinct cl.domain), min(u.createDate))
            from CampaignLink cl join Url u on u.id = cl.id.urlId
            where cl.id.campaignId in :campaignIds
            group by cl.id.campaignId""")
    List<CampaignLinkCounts> countByCampaignIdIn(@Param("campaignIds") Collection<Long> campaignIds);

    @Query("""
            select new cc.hrva.urlshortener.dto.campaign.CampaignReportCounts(cl.id.campaignId, count(distinct r.domain),
                count(distinct case when r.suspendedAt is not null then r.domain end))
            from CampaignLink cl join ReportedDomain r on r.domain = cl.domain
            where cl.id.campaignId in :campaignIds
            group by cl.id.campaignId""")
    List<CampaignReportCounts> countReportsByCampaignIdIn(@Param("campaignIds") Collection<Long> campaignIds);

    @Query("""
            select new cc.hrva.urlshortener.dto.campaign.CampaignDomainCount(cl.domain, count(cl))
            from CampaignLink cl
            where cl.id.campaignId = :campaignId
            group by cl.domain
            order by count(cl) desc, cl.domain""")
    List<CampaignDomainCount> countDomainsByCampaignId(@Param("campaignId") long campaignId);

}
