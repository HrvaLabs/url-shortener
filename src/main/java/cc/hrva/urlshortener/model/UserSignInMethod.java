package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Getter
@Builder
@Immutable
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_sign_in_method")
public class UserSignInMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    @Enumerated(EnumType.STRING)
    private SignInMethod method;
    private LocalDateTime firstUsedAt;
    private LocalDateTime lastUsedAt;

}
