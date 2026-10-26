package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySignInDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface PasskeySignInService {

    PasskeyOptionsResponse options(HttpServletRequest request);
    ResponseEntity<JWTTokenDto> verify(PasskeySignInDto dto, HttpServletRequest request);

}
