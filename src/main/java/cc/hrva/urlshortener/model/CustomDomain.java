package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@BatchSize(size = 50)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "custom_domain")
public class CustomDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;
    private String hostname;
    private String hostnameKey;
    @Enumerated(EnumType.STRING)
    private CustomDomainStatus status;
    private String reason;
    @Convert(converter = NewlineListConverter.class)
    private List<String> applyFlags;
    private String reviewNote;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String cloudflareId;
    private String cloudflareStatus;
    private String cloudflareSslStatus;
    private String lastError;
    @Enumerated(EnumType.STRING)
    private DomainProblem problem;
    private LocalDateTime nextCheckAt;
    private LocalDateTime lastCheckedAt;
    private int checkCount;
    private LocalDateTime activatedAt;
    @Enumerated(EnumType.STRING)
    private DomainDisableReason disabledReason;
    private String disabledNote;
    private LocalDateTime disabledAt;
    private LocalDateTime deletedAt;
    private boolean cloudflareDeletePending;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
