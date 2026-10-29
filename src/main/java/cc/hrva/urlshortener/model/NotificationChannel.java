package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.LastDeliveryStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notification_channel")
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User owner;
    private String name;
    @Enumerated(EnumType.STRING)
    private ChannelType type;
    private boolean enabled;
    @ToString.Exclude
    private String targetSealed;
    @ToString.Exclude
    private String accessTokenSealed;
    @ToString.Exclude
    private String signingSecretSealed;
    private String chatId;
    private String maskedTarget;
    private int failedDays;
    private LocalDate lastFailedDay;
    private LocalDateTime autoDisabledAt;
    private LocalDateTime lastDeliveryAt;
    @Enumerated(EnumType.STRING)
    private LastDeliveryStatus lastDeliveryStatus;
    private Integer lastDeliveryHttp;
    private String lastDeliveryError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
