package cc.hrva.urlshortener.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
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
public class ResetToken {

    @Id
    @SequenceGenerator(name = "reset_token_id_seq", allocationSize = 1)
    @GeneratedValue(generator = "reset_token_id_seq", strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne
    private User user;

    private String tokenHash;

    private LocalDateTime createDate;

    private LocalDateTime expirationDate;

    private boolean active;

    @PrePersist
    public void onCreate() {
        this.createDate = LocalDateTime.now();
        this.active = true;
    }

}
