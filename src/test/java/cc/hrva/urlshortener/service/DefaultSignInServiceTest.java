package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.impl.DefaultSignInService;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSignInServiceTest {

    private static final String PENDING_TOKEN = "q6Wc1oXyZ0bJ2t3r4s5u6v7w8x9y0zAaBbCcDdEeFfG";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private UserService userService;

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private AppProperties appProperties;

    @Mock
    private PendingLoginStore pendingLoginStore;

    @Mock
    private TwoStepMethods twoStepMethods;

    @Mock
    private UserToUserDtoConverter userToUserDtoConverter;

    @Mock
    private SignInMethodService signInMethodService;

    private SignInService signInService;

    @BeforeEach
    void setUp() {
        signInService = new DefaultSignInService(userService, tokenProvider, appProperties, pendingLoginStore,
                twoStepMethods, userToUserDtoConverter, signInMethodService);
    }

    @Test
    void rememberMeGivesAThirtyDayToken() {
        final var user = user();
        when(tokenProvider.createToken(any(), eq(2_592_000L))).thenReturn("eyJ.thirty.days");

        final var response = signInService.signIn(user, SignInMethod.PASSWORD, true);

        assertThat(response.getBody().getToken()).isEqualTo("Bearer eyJ.thirty.days");
        verify(appProperties, never()).getJwtTokenValiditySeconds();
    }

    @Test
    void withoutRememberMeTheTokenLastsTheConfiguredTime() {
        final var user = user();
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36_000L);
        when(tokenProvider.createToken(any(), eq(36_000L))).thenReturn("eyJ.ten.hours");

        final var response = signInService.signIn(user, SignInMethod.PASSWORD, false);

        assertThat(response.getBody().getToken()).isEqualTo("Bearer eyJ.ten.hours");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void signInSetsTheAuthorizationHeaderAndMarksTheLogin(final CapturedOutput output) {
        final var user = user();
        final var userDto = UserDto.builder().id(7L).email("user@example.com").twoStepEnabled(true).build();
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36_000L);
        when(tokenProvider.createToken(any(), anyLong())).thenReturn("eyJ.token");
        when(userToUserDtoConverter.convert(user)).thenReturn(userDto);

        final var response = signInService.signIn(user, SignInMethod.PASSWORD, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(JwtFilter.AUTHORIZATION_HEADER)).isEqualTo("Bearer eyJ.token");
        assertThat(response.getBody().getToken()).isEqualTo("Bearer eyJ.token");
        assertThat(response.getBody().getUser()).isEqualTo(userDto);
        assertThat(response.getBody().getTwoStepRequired()).isNull();
        assertThat(response.getBody().getPendingToken()).isNull();
        verify(userService).userHasLoggedIn(eq(user), any(LocalDateTime.class));

        final var authentication = ArgumentCaptor.forClass(Authentication.class);
        verify(tokenProvider).createToken(authentication.capture(), eq(36_000L));
        assertThat(authentication.getValue().getName()).isEqualTo("user@example.com");
        assertThat(authentication.getValue().getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(output).contains("Signed in user id=7").doesNotContain("user@example.com").doesNotContain("eyJ.token");
    }

    @ParameterizedTest
    @EnumSource(SignInMethod.class)
    void signInRecordsItsMethodAtTheTimeOfTheSignIn(final SignInMethod method) {
        final var user = user();
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(36_000L);
        when(tokenProvider.createToken(any(), anyLong())).thenReturn("eyJ.token");
        final var before = LocalDateTime.now();

        signInService.signIn(user, method, false);

        final var signedInAt = ArgumentCaptor.forClass(LocalDateTime.class);
        final var order = inOrder(userService, signInMethodService);
        order.verify(userService).userHasLoggedIn(eq(user), signedInAt.capture());
        order.verify(signInMethodService).record(7L, method, signedInAt.getValue());
        assertThat(signedInAt.getValue()).isBetween(before, LocalDateTime.now());
    }

    @Test
    void requireSecondStepCarriesTheFirstStepMethodAndRecordsNothing() {
        final var user = user();
        when(pendingLoginStore.create(7L, false, SignInMethod.GITHUB)).thenReturn(PENDING_TOKEN);
        when(twoStepMethods.of(user)).thenReturn(List.of(TwoStepMethod.TOTP));

        final var response = signInService.requireSecondStep(user, SignInMethod.GITHUB, false);

        assertThat(response.getBody().getPendingToken()).isEqualTo(PENDING_TOKEN);
        verifyNoInteractions(signInMethodService);
    }

    @Test
    void requireSecondStepIssuesNoJwt() {
        final var user = user();
        when(pendingLoginStore.create(7L, true, SignInMethod.PASSWORD)).thenReturn(PENDING_TOKEN);
        when(twoStepMethods.of(user)).thenReturn(List.of(TwoStepMethod.TOTP, TwoStepMethod.RECOVERY_CODE));

        final var response = signInService.requireSecondStep(user, SignInMethod.PASSWORD, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().containsHeader(JwtFilter.AUTHORIZATION_HEADER)).isFalse();
        assertThat(JSON.readTree(JSON.writeValueAsString(response.getBody()))).isEqualTo(JSON.readTree("""
                {"token":null,"user":null,"twoFactorRequired":true,"twoStepRequired":true,"pendingToken":"%s","methods":["TOTP","RECOVERY_CODE"]}
                """.formatted(PENDING_TOKEN)));
        verifyNoInteractions(tokenProvider, userService, userToUserDtoConverter);
    }

    @Test
    void aFailedMethodLookupLeavesNoPendingToken() {
        final var user = user();
        when(twoStepMethods.of(user)).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> signInService.requireSecondStep(user, SignInMethod.PASSWORD, true))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(pendingLoginStore, tokenProvider);
    }

    private static User user() {
        return User.builder()
                .id(7L)
                .email("user@example.com")
                .emailVerified(true)
                .active(true)
                .twoStepEnabled(true)
                .twoFactorEnabled(true)
                .authorities(List.of(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
    }

}
