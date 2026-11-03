package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.InspectionRequester;
import cc.hrva.urlshortener.model.enums.InspectionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Table(name = "link_inspection")
public class LinkInspection {

    @Id
    private Long urlId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InspectionState screenshotState;

    private LocalDateTime screenshotRequestedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private InspectionRequester screenshotRequestedBy;

    private LocalDateTime screenshotTakenAt;

    @Column(columnDefinition = "TEXT")
    private String screenshotUrl;

    @Column(columnDefinition = "TEXT")
    private String pageTitle;

    private Integer pageStatus;

    private String screenshotFailure;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InspectionState vtState;

    private LocalDateTime vtRequestedAt;

    private LocalDateTime vtCheckedAt;

    @Column(columnDefinition = "TEXT")
    private String vtUrl;

    @Column(length = 100)
    private String vtReportId;

    private Integer vtMalicious;

    private Integer vtSuspicious;

    private Integer vtHarmless;

    private Integer vtUndetected;

    private LocalDateTime vtAnalysedAt;

    private String vtFailure;

    private LocalDateTime vtSubmittedAt;

    private Integer vtNextCandidate;

    @Column(length = 64)
    private String vtSubmittedId;

}
