package cc.hrva.urlshortener.configuration.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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
    private Campaign campaign = new Campaign();

    @Valid
    @NotNull
    private TrafficAlerts trafficAlerts = new TrafficAlerts();

    @Valid
    @NotNull
    private Retention retention = new Retention();

    @Valid
    @NotNull
    private Notifications notifications = new Notifications();

    @Valid
    @NotNull
    private DestinationCheck destinationCheck = new DestinationCheck();

    @Valid
    @NotNull
    private Domains domains = new Domains();

    @Valid
    @NotNull
    private Cloudflare cloudflare = new Cloudflare();

    @Valid
    @NotNull
    private Moderation moderation = new Moderation();

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
    public static class Campaign {

        private boolean enabled = true;

        @Min(2)
        private int links = 3;

        @Min(2)
        private int domains = 3;

        @NotNull
        @DurationMin(hours = 1, message = "must be at least 1 hour")
        private Duration window = Duration.ofHours(24);

    }

    @Data
    public static class TrafficAlerts {

        private boolean enabled = true;

        @Min(5)
        private int linkClicks = 25;

        @Min(10)
        @Max(99)
        private int sharePercent = 50;

        @Min(10)
        private int minAnonymousClicks = 100;

    }

    @Data
    public static class Retention {

        @Min(1)
        private int emailLogDays = 90;

        @Min(1)
        private int auditLogMonths = 12;

    }

    @Data
    public static class Notifications {

        @ToString.Exclude
        private String key;

    }

    @Data
    public static class DestinationCheck {

        private boolean enabled = true;

        private boolean catchUpOnStartup = true;

        @NotNull
        @DurationMin(minutes = 1, message = "must be at least 1 minute")
        private Duration budget = Duration.ofHours(2);

    }

    @Data
    public static class Domains {

        private boolean enabled = true;

        @Min(1)
        private int maxActive = 90;

        @Min(1)
        private int freeAllowance = 100;

        @Min(1)
        private int perUser = 1;

        @NotNull
        private List<String> reserved = List.of("hrva.cc", "hrvalabs.net");

        @NotNull
        private List<String> primaryHosts = List.of("hrva.cc", "domains.hrva.cc");

        @NotNull
        private List<String> ignoredHostnames = List.of("test.karlo.cc");

        @NotBlank
        private String cnameTarget = "domains.hrva.cc";

    }

    @Data
    public static class Cloudflare {

        @ToString.Exclude
        private String apiToken;

        private String zoneId;

        @NotBlank
        private String apiBase = "https://api.cloudflare.com/client/v4";

    }

    @Data
    public static class Moderation {

        @Min(1)
        @Max(100)
        private int anonymousLinksPerHour = 10;

        private boolean customAliases = true;
        private boolean safeBrowsingCheck = true;
        private boolean blocklistCheck = true;
        private boolean suspiciousLinkCheck = true;
        private boolean nightlySafeBrowsing = true;
        private boolean sendEmails = true;
        private boolean campaignHold = true;

        @Min(1)
        @Max(90)
        private int campaignHoldDays = 7;

        @Valid
        @NotNull
        private Emails emails = new Emails();

    }

    @Data
    public static class Emails {

        private boolean linkBlocked = true;
        private boolean malwareFound = true;
        private boolean deadLinks = true;
        private boolean linkExpiring = true;
        private boolean weeklySummary = true;
        private boolean adminDaily = true;
        private boolean newUserNotice = true;
        private boolean customDomain = true;

    }

}
