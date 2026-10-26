package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeySecondStepDto;
import cc.hrva.urlshortener.dto.PendingTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface SecondStepService {

    ResponseEntity<JWTTokenDto> completeWithCode(TotpSecondStepDto dto, HttpServletRequest request);
    PasskeyOptionsResponse passkeyOptions(PendingTokenDto dto, HttpServletRequest request);
    ResponseEntity<JWTTokenDto> completeWithPasskey(PasskeySecondStepDto dto, HttpServletRequest request);

}
