package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.PasskeyKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "passkey_credential")
public class PasskeyCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
    private String credentialId;
    @Column(columnDefinition = "TEXT")
    private String publicKey;
    @Column(columnDefinition = "TEXT")
    private String attestationObject;
    private long signCount;
    private boolean uvInitialized;
    private boolean backupEligible;
    private boolean backupState;
    private Boolean discoverable;
    private String transports;
    private String aaguid;
    private String label;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;

    public PasskeyKind kind() {
        if (Boolean.FALSE.equals(discoverable)) {
            return PasskeyKind.SECOND_STEP_ONLY;
        }
        if (backupEligible) {
            return PasskeyKind.SYNCED;
        }
        if (Boolean.TRUE.equals(discoverable)) {
            return PasskeyKind.DEVICE_BOUND;
        }
        return PasskeyKind.UNKNOWN;
    }

}
