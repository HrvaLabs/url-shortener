package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.SignInService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSignInService implements SignInService {

    private static final long REMEMBER_ME_VALIDITY_SECONDS = 2_592_000L;
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserService userService;
    private final TokenProvider tokenProvider;
    private final AppProperties appProperties;
    private final PendingLoginStore pendingLoginStore;
    private final TwoStepMethods twoStepMethods;
    private final UserToUserDtoConverter userToUserDtoConverter;

    @Override
    @Transactional
    public ResponseEntity<JWTTokenDto> signIn(final User user, final boolean rememberMe) {
        userService.userHasLoggedIn(user);

        final var token = BEARER_PREFIX + tokenProvider.createToken(authenticationOf(user), validitySeconds(rememberMe));
        final var httpHeaders = new HttpHeaders();
        httpHeaders.add(JwtFilter.AUTHORIZATION_HEADER, token);
        final var jwtTokenDto = JWTTokenDto.builder()
                .token(token)
                .user(userToUserDtoConverter.convert(user))
                .build();

        return new ResponseEntity<>(jwtTokenDto, httpHeaders, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<JWTTokenDto> requireSecondStep(final User user, final boolean rememberMe) {
        final var methods = twoStepMethods.of(user);
        final var pendingToken = pendingLoginStore.create(user.getId(), rememberMe);
        log.info("Second step required user id={}", user.getId());

        return new ResponseEntity<>(JWTTokenDto.twoStepRequired(pendingToken, methods), HttpStatus.OK);
    }

    private long validitySeconds(final boolean rememberMe) {
        return rememberMe ? REMEMBER_ME_VALIDITY_SECONDS : appProperties.getJwtTokenValiditySeconds();
    }

    private static UsernamePasswordAuthenticationToken authenticationOf(final User user) {
        final var authorities = user.getAuthorities().stream()
                .map(authority -> new SimpleGrantedAuthority(authority.getName()))
                .toList();

        return new UsernamePasswordAuthenticationToken(user.getEmail(), null, authorities);
    }

}
