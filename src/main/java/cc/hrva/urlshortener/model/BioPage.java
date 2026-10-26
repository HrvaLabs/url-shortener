package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioTheme;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
@Table(name = "bio_page")
public class BioPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User owner;
    private String handle;
    private String displayName;
    private String bio;
    @Enumerated(EnumType.STRING)
    private BioAvatarKind avatarKind;
    private String avatarEmoji;
    @Enumerated(EnumType.STRING)
    private BioAccent accent;
    @Enumerated(EnumType.STRING)
    private BioTheme theme;
    private boolean indexable;
    private LocalDateTime hiddenAt;
    private String hiddenReason;
    private String hiddenBy;
    private String handleRevokedReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        final var now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
        if (this.displayName == null) {
            this.displayName = "";
        }
        if (this.bio == null) {
            this.bio = "";
        }
        if (this.avatarKind == null) {
            this.avatarKind = BioAvatarKind.INITIALS;
        }
        if (this.accent == null) {
            this.accent = BioAccent.BLUE;
        }
        if (this.theme == null) {
            this.theme = BioTheme.AUTO;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isPublic() {
        return handle != null && hiddenAt == null && !Boolean.FALSE.equals(owner.getActive());
    }

}
