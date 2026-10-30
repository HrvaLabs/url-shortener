package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import org.springframework.http.ResponseEntity;

public interface SignInService {

    ResponseEntity<JWTTokenDto> signIn(User user, SignInMethod method, boolean rememberMe);
    ResponseEntity<JWTTokenDto> requireSecondStep(User user, SignInMethod method, boolean rememberMe);

}
