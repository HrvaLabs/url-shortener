package cc.hrva.urlshortener.configuration.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("app")
public class AppProperties {

    @NotBlank
    private String frontendUrl;

    @NotBlank
    private String jwtBase64Secret;

    @NotNull
    @Positive
    private Long shortUrlLength;

    @NotNull
    @Positive
    private Long jwtTokenValiditySeconds;

    @NotNull
    @Positive
    private Long apiKeyCallsLimit;

    @NotNull
    @Positive
    private Long apiKeyExpirationInMonths;

    @NotNull
    @Positive
    private Long resetTokenExpirationInHours;

    @NotNull
    @Positive
    private Long verificationTokenExpirationInHours = 48L;

    @NotNull
    @Positive
    private Long userApiKeySlots;

    @NotBlank
    private String emailSenderAddress;

    @NotNull
    @Positive
    private Long ipRetentionDurationInHours;

    @NotNull
    @Positive
    private Long deactivateUserAccountAfterDays;

    @NotBlank
    private String contactEmail;

    @NotBlank
    private String appName;

    @NotBlank
    private String serverUrl;

    @NotNull
    @Positive
    private Long maxLoginAttempts;

    @NotNull
    @Positive
    private Long urlExpirationNotificationHours;

    private boolean registrationEnabled = true;
    private boolean anonymousUrlCreationEnabled = true;
    private boolean safeBrowsingFailClosed = false;

    @ToString.Exclude
    private String spamhausDqsKey;

    @ToString.Exclude
    private String discordWebhookUrl;

    @Valid
    @NotNull
    private Mcp mcp = new Mcp();

    @Valid
    @NotNull
    private Blocklists blocklists = new Blocklists();

    @Valid
    @NotNull
    private Analytics analytics = new Analytics();

    @Valid
    @NotNull
    private Passkeys passkeys = new Passkeys();

    @Valid
    @NotNull
    private Digest digest = new Digest();

    @Valid
    @NotNull
    private Burst burst = new Burst();

    @Valid
    @NotNull
    private Retention retention = new Retention();

    @Data
    public static class Mcp {

        @NotBlank
        private String issuer;

        @NotBlank
        @ToString.Exclude
        private String signingKey;

    }

    @Data
    public static class Blocklists {

        @NotNull
        @Positive
        private Long maxRedisMb = 256L;

    }

    @Data
    public static class Analytics {

        @NotNull
        private ZoneId zone = ZoneId.of("Europe/Zagreb");

    }

    @Data
    public static class Passkeys {

        @NotBlank
        private String rpId;

        @NotEmpty
        private List<String> allowedOrigins;

    }

    @Data
    public static class Digest {

        @Valid
        @NotNull
        private Weekly weekly = new Weekly();

    }

    @Data
    public static class Weekly {

        private boolean enabled = true;
        private boolean email = true;

    }

    @Data
    public static class Burst {

        @Min(2)
        private int links = 5;

        @NotNull
        @DurationMin(seconds = 1, message = "must be at least 1 second")
        private Duration window = Duration.ofHours(1);

    }

    @Data
    public static class Retention {

        @Min(1)
        private int emailLogDays = 90;

        @Min(1)
        private int auditLogMonths = 12;

    }

}
