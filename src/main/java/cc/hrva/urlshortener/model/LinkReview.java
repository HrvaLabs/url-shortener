package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.ReviewResolution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
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
@Table(name = "link_review")
public class LinkReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    private Url url;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String reasons;
    @Column(nullable = false)
    private LocalDateTime flaggedAt;
    private LocalDateTime resolvedAt;
    private String resolvedBy;
    @Enumerated(EnumType.STRING)
    private ReviewResolution resolution;

    @PrePersist
    public void onCreate() {
        if (this.flaggedAt == null) {
            this.flaggedAt = LocalDateTime.now();
        }
    }

}
