package cc.hrva.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "link_screenshot")
public class LinkScreenshot {

    public static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;

    @Id
    private Long urlId;

    @Column(nullable = false, length = MAX_IMAGE_BYTES)
    private byte[] image;

    @Column(nullable = false, length = 30)
    private String contentType;

}
