package cc.hrva.urlshortener.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Email {

    private String sender;
    private String[] receivers;
    private String[] bcc;
    private String subject;
    private String text;
    private String plainText;

}
