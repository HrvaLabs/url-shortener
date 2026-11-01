package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.LoginDto;
import cc.hrva.urlshortener.dto.RegistrationResponse;
import cc.hrva.urlshortener.dto.UserRegisterDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.ResponseEntity;

public interface AuthService {

    RegistrationResponse register(UserRegisterDto userRegisterDto);

    Optional<RegistrationResponse> registeredMeanwhile(String email);
    ResponseEntity<JWTTokenDto> login(LoginDto loginDto, HttpServletRequest request);
    void verifyEmail(String token);
    void resendVerificationEmail(String email);

}
