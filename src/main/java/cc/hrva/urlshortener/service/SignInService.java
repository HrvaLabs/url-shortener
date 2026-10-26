package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.model.User;
import org.springframework.http.ResponseEntity;

public interface SignInService {

    ResponseEntity<JWTTokenDto> signIn(User user, boolean rememberMe);
    ResponseEntity<JWTTokenDto> requireSecondStep(User user, boolean rememberMe);

}
