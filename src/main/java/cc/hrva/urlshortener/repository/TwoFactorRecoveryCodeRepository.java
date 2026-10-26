package cc.hrva.urlshortener.repository;

import java.util.List;
import cc.hrva.urlshortener.model.TwoFactorRecoveryCode;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TwoFactorRecoveryCodeRepository extends JpaRepository<TwoFactorRecoveryCode, Long> {

    List<TwoFactorRecoveryCode> findByUserAndUsedFalse(User user);
    long countByUserAndUsedFalse(User user);
    void deleteByUser(User user);

}
