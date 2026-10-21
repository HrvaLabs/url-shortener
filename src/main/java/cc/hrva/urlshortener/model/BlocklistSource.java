package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.BlocklistAction;
import jakarta.persistence.Column;
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
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "blocklist_source")
public class BlocklistSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String url;
    @Enumerated(EnumType.STRING)
    private BlocklistAction action;
    private boolean enabled;
    private LocalDateTime lastFetchedAt;
    private LocalDateTime lastSuccessAt;
    private Long domainCount;
    @Column(columnDefinition = "TEXT")
    private String lastError;
    private String etag;
    private String lastModified;

}
