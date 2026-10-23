package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.AuditAction;
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
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String performedBy;
    @Enumerated(EnumType.STRING)
    private AuditAction action;
    private boolean failed;
    private String targetType;
    private String targetIdentifier;
    @Column(columnDefinition = "TEXT")
    private String details;
    private LocalDateTime performedAt;

    @PrePersist
    public void onCreate() {
        this.performedAt = LocalDateTime.now();
    }

}
