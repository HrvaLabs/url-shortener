package cc.hrva.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.UniqueElements;

public record DomainBulkBlockDto(
        @NotNull(message = DomainBulkBlockDto.DOMAINS_REQUIRED)
        @Size(min = 1, max = DomainBulkBlockDto.MAX_DOMAINS, message = DomainBulkBlockDto.DOMAINS_REQUIRED)
        @UniqueElements(message = DomainBulkBlockDto.DOMAINS_REPEATED)
        List<@NotBlank(message = DomainBulkBlockDto.DOMAIN_REQUIRED) String> domains,
        @NotBlank(message = "Reason is required")
        @Size(max = 255, message = "Reason must be 255 characters or fewer")
        String reason) {

    public static final int MAX_DOMAINS = 100;
    public static final String DOMAINS_REQUIRED = "Select between 1 and 100 domains";
    public static final String DOMAINS_REPEATED = "Select each domain only once";
    public static final String DOMAIN_REQUIRED = "Every selected domain needs a name";

}
