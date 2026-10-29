package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.EmailStatus;
import cc.hrva.urlshortener.model.enums.NotificationType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "email_log")
public class EmailLog {

    public static final String STATUS_SENDING = EmailStatus.SENDING.name();
    public static final String STATUS_SENT = EmailStatus.SENT.name();
    public static final String STATUS_FAILED = EmailStatus.FAILED.name();
    public static final String STATUS_RESENT = EmailStatus.RESENT.name();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sender;
    private String recipient;
    private String subject;
    private String status;
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String body;
    @Enumerated(EnumType.STRING)
    private NotificationType consentType;
    private Long userId;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isResendable() {
        return STATUS_FAILED.equals(status) && body != null;
    }

}
