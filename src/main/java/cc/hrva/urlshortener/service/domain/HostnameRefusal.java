package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.exception.FieldValidationException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HostnameRefusal {

    BLANK("Enter a domain, like go.example.com"),
    TOO_LONG("A domain can be 253 characters at most."),
    BAD_SHAPE("Enter a domain like go.example.com: letters, digits and hyphens, with dots between the parts."),
    APEX("Use a subdomain, like go.example.com. Root domains like example.com aren't supported yet."),
    NOT_OWNABLE("Enter a domain you own, like go.example.com."),
    RESERVED("This domain can't be used on hrva.cc."),
    SYMBOLS("Emoji and symbol domains can't be used. Use a domain made of letters and digits.");

    public static final String FIELD = "hostname";

    private final String text;

    public FieldValidationException toException() {
        return new FieldValidationException(FIELD, text);
    }

}
