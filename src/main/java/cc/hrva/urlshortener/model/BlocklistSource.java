package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.SourceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
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
    @Builder.Default
    @Convert(converter = NewlineListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> fallbackUrls = List.of();
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private SourceKind kind = SourceKind.DOMAINS;
    @Enumerated(EnumType.STRING)
    private BlocklistAction action;
    @Builder.Default
    private boolean enabled = true;
    @Builder.Default
    private int refreshIntervalHours = 6;
    private LocalDateTime lastFetchedAt;
    private LocalDateTime lastSuccessAt;
    private String lastSuccessUrl;
    private Long domainCount;
    private String listVersion;
    @Column(columnDefinition = "TEXT")
    private String lastError;
    private String etag;
    private String lastModified;

}
