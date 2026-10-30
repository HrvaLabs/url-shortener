package cc.hrva.urlshortener.repository;

import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {

    Optional<PasskeyCredential> findByCredentialId(String credentialId);
    List<PasskeyCredential> findByUserOrderByCreatedAtDesc(User user);
    Optional<PasskeyCredential> findByIdAndUser(Long id, User user);
    long countByUser(User user);

    @Query("select count(p) from PasskeyCredential p where p.user.id = :userId and p.discoverable = true")
    long countSignInPasskeysByUserId(@Param("userId") Long userId);

    void deleteByUser(User user);

}
