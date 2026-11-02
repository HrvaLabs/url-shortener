package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface OAuthSignInService {

    ResponseEntity<JWTTokenDto> exchange(String code, String verifier, HttpServletRequest request);

}
