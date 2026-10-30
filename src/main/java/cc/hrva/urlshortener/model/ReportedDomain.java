package cc.hrva.urlshortener.model;

import jakarta.persistence.Entity;
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
@Table(name = "reported_domain")
public class ReportedDomain {

    @Id
    private String domain;
    private String registrar;
    private String abuseEmail;
    private String reportedBy;
    private LocalDateTime reportedAt;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime suspendedAt;

}
