package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findApiKeyByKey(String key);
    List<ApiKey> findByOwner(User owner);
    List<ApiKey> findByOwnerAndActiveTrue(User owner);
    List<ApiKey> findByExpirationDateIsLessThanEqualAndActiveTrue(LocalDateTime createDate);

    @Modifying
    @Query("delete from ApiKey k where k.id = :id and k.active = false")
    int deleteInactiveById(@Param("id") Long id);

    @Modifying
    @Query("delete from ApiKey k where k.owner.id = :ownerId and k.active = false")
    int deleteInactiveByOwnerId(@Param("ownerId") Long ownerId);

}
