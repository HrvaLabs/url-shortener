package cc.hrva.urlshortener.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.model.enums.PlatformType;
import cc.hrva.urlshortener.model.enums.ThreatEntryType;
import cc.hrva.urlshortener.model.enums.ThreatType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Table(uniqueConstraints = @UniqueConstraint(name = "ux_url_domain_short_url", columnNames = {"domain_id", "short_url"}))
public class Url {

    @Id
    @SequenceGenerator(name = "url_id_seq", allocationSize = 1)
    @GeneratedValue(generator = "url_id_seq", strategy = GenerationType.SEQUENCE)
    private Long id;

    @URL(message = "Enter a valid URL (e.g. https://example.com)")
    @NotBlank(message = "URL is required")
    @Column(length = 2048)
    private String longUrl;

    private String shortUrl;

    @ManyToOne
    private User owner;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "domain_id")
    private CustomDomain domain;

    private LocalDateTime createDate;
    private LocalDateTime lastAccessed;
    private LocalDateTime expirationDate;
    private Long visits;
    private Long visitLimit;
    private LocalDateTime lastSafeBrowsingCheck;

    @Enumerated(EnumType.STRING)
    private PlatformType platformType;

    @Enumerated(EnumType.STRING)
    private ThreatEntryType threatEntryType;

    @Enumerated(EnumType.STRING)
    private ThreatType threatType;

    @Enumerated(EnumType.STRING)
    private BlockReason blockReason;

    private String blockDetail;
    private LocalDateTime blockedAt;
    private String finalUrl;
    private LocalDateTime chainCheckedAt;

    @Enumerated(EnumType.STRING)
    private CreatedVia createdVia;

    @Column(name = "destination_checked_at", insertable = false, updatable = false)
    private LocalDateTime destinationCheckedAt;

    @Column(name = "destination_dead_since", insertable = false, updatable = false)
    private LocalDateTime destinationDeadSince;

    @Column(name = "destination_fail_streak", insertable = false, updatable = false)
    private Integer destinationFailStreak;

    @Column(name = "destination_last_status", insertable = false, updatable = false)
    private String destinationLastStatus;

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "url_tags", joinColumns = @JoinColumn(name = "URL_ID"))
    @Column(name = "TAG")
    private Set<String> tags;

    @JsonIgnore
    private String passwordHash;

    private boolean active;

    private boolean warningPage;

    @PrePersist
    public void onCreate() {
        this.visits = 0L;
        this.createDate = LocalDateTime.now();
        this.active = blockReason == null && threatType == null;
    }

    public void verifyUrlValidity(final Url url) {
        if (this.visits >= Optional.ofNullable(visitLimit).orElse(this.visits + 1)) {
            this.active = false;
        }
    }

    @Transient
    public Long domainId() {
        return domain == null ? null : domain.getId();
    }

    public void clearForAnonymousUser() {
        this.owner = null;
        this.domain = null;
    }

}
