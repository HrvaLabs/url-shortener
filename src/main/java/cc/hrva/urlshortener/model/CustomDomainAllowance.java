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
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "custom_domain_allowance")
public class CustomDomainAllowance {

    @Id
    private Long userId;
    private int maxDomains;
    private String reason;
    private String grantedBy;
    private LocalDateTime grantedAt;

}
