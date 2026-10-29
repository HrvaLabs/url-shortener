package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.NotificationConsent;
import cc.hrva.urlshortener.model.enums.NotificationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationConsentRepository extends JpaRepository<NotificationConsent, Long> {

    Optional<NotificationConsent> findFirstByOwnerIdAndTypeAndChannelIdIsNullAndRevokedAtIsNull(Long userId, NotificationType type);

    List<NotificationConsent> findByOwnerIdAndChannelIdIsNullAndRevokedAtIsNull(Long userId);

    List<NotificationConsent> findByChannelIdAndRevokedAtIsNull(Long channelId);

    List<NotificationConsent> findByChannelIdInAndRevokedAtIsNull(Collection<Long> channelIds);

    boolean existsByChannelIdAndTypeAndRevokedAtIsNull(Long channelId, NotificationType type);

    @Query("""
            select count(c) > 0 from NotificationConsent c
            where c.owner.id = :userId and c.type = :type and c.channelId is null and c.revokedAt is null and c.owner.active = true""")
    boolean existsActiveEmailConsent(@Param("userId") long userId, @Param("type") NotificationType type);

    @Query("""
            select c.owner.id from NotificationConsent c
            where c.type = :type and c.channelId is null and c.revokedAt is null and c.owner.active = true and c.owner.id in :userIds""")
    List<Long> findConsentingUserIds(@Param("type") NotificationType type, @Param("userIds") Collection<Long> userIds);

    @Query("""
            select distinct c.owner.id from NotificationConsent c
            where c.type = :type and c.channelId is null and c.revokedAt is null and c.owner.active = true""")
    List<Long> findAllConsentingUserIds(@Param("type") NotificationType type);

    List<NotificationConsent> findByOwnerIdOrderByGrantedAtAscIdAsc(Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from NotificationConsent c where c.owner.id = :userId")
    int deleteByOwnerId(@Param("userId") Long userId);

}
