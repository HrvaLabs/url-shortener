package cc.hrva.urlshortener.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.PlatformType;
import cc.hrva.urlshortener.model.enums.ThreatEntryType;
import cc.hrva.urlshortener.model.enums.ThreatType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.validator.constraints.URL;
import org.springframework.cache.annotation.CacheEvict;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Url {

    @Id
    @SequenceGenerator(name = "url_id_seq", allocationSize = 1)
    @GeneratedValue(generator = "url_id_seq", strategy = GenerationType.SEQUENCE)
    private Long id;

    @URL(message = "Enter a valid URL (e.g. https://example.com)")
    @NotBlank(message = "URL is required")
    private String longUrl;

    @Column(unique = true)
    private String shortUrl;

    @ManyToOne
    private User owner;

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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "url_tags", joinColumns = @JoinColumn(name = "URL_ID"))
    @Column(name = "TAG")
    private Set<String> tags;

    @JsonIgnore
    private String passwordHash;

    private boolean active;

    @PrePersist
    public void onCreate() {
        this.visits = 0L;
        this.createDate = LocalDateTime.now();
        this.active = true;
    }

    public Url onVisit() {
        this.visits++;
        this.lastAccessed = LocalDateTime.now();
        verifyUrlValidity(this);
        return this;
    }

    public void verifyUrlValidity(final Url url) {
        if (this.visits >= Optional.ofNullable(visitLimit).orElse(this.visits + 1)) {
            this.active = false;
        }
    }

    public void clearForAnonymousUser() {
        this.owner = null;
    }

}
