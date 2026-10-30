package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.LocalDateTime;

public record SignInMethodState(SignInMethod method, LocalDateTime turnedOffAt) {

    public boolean turnedOff() {
        return turnedOffAt != null;
    }

}
