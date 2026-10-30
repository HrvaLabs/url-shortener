package cc.hrva.urlshortener.dto.abuse;

import cc.hrva.urlshortener.dto.DomainBulkBlockDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record ReportedDomainsDto(
        @NotNull(message = DomainBulkBlockDto.DOMAINS_REQUIRED)
        @Size(min = 1, max = DomainBulkBlockDto.MAX_DOMAINS, message = DomainBulkBlockDto.DOMAINS_REQUIRED)
        @UniqueElements(message = DomainBulkBlockDto.DOMAINS_REPEATED)
        List<@NotBlank(message = DomainBulkBlockDto.DOMAIN_REQUIRED) @Size(max = 253, message = DomainBulkBlockDto.DOMAIN_REQUIRED) String> domains,
        @Size(max = 255, message = "Use at most 255 characters for the registrar") String registrar,
        @Size(max = 320, message = "Use at most 320 characters for the abuse address") String abuseEmail) {
}
