package cc.hrva.urlshortener.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "bio_handle_hold")
public class BioHandleHold {

    @Id
    private String handle;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id")
    private BioPage page;
    private LocalDateTime heldUntil;
    private LocalDateTime createdAt;

}
