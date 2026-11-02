package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ConnectRequestExpiredException;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppDto;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.UserService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectControllerTest {

    private static final String HANDLE = "handle";
    private static final String CLIENT_ID = "client-1";
    private static final String EMAIL = "ana@hrva.cc";

    @Mock
    private ConnectRequestStore connectRequestStore;

    @Mock
    private RegisteredClientRepository registeredClientRepository;

    @Mock
    private UserService userService;

    @Mock
    private ConnectedAppsService connectedAppsService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final User user = User.builder().id(7L).email(EMAIL).active(true).build();
    private final UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(EMAIL, null, List.of());
    private ConnectController controller;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getMcp().setIssuer("https://hrva.cc");
        final var claude = new AppProperties.KnownApp();
        claude.setNames(List.of("Claude"));
        claude.setDomains(List.of("claude.ai"));
        appProperties.getMcp().setKnownApps(List.of(claude));
        controller = new ConnectController(connectRequestStore, registeredClientRepository, userService, appProperties,
                new ConnectDestinations(appProperties), connectedAppsService, eventPublisher);
    }

    @Test
    void approvingAnAppTheAccountHasntConnectedPublishesTheEmailEventInUtc() {
        pendingRequestTo("https://evil.example/cb");
        when(connectedAppsService.findConnectedApps(EMAIL)).thenReturn(List.of());
        when(connectRequestStore.approve(HANDLE, EMAIL)).thenReturn("one-time");
        final var before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        controller.approve(HANDLE, authentication);

        final var event = ArgumentCaptor.forClass(AppConnectedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().user()).isSameAs(user);
        assertThat(event.getValue().destination()).isEqualTo(new ConnectRequestView("Claude", "evil.example", false, true, null, false));
        assertThat(event.getValue().connectedAt()).isAfter(before).isBefore(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
    }

    @Test
    void approvingAnAppTheAccountAlreadyConnectedSendsNoEmail() {
        pendingRequestTo("https://claude.ai/cb");
        when(connectedAppsService.findConnectedApps(EMAIL)).thenReturn(List.of(new ConnectedAppDto(CLIENT_ID, "Claude", Instant.now(), null)));
        when(connectRequestStore.approve(HANDLE, EMAIL)).thenReturn("one-time");

        controller.approve(HANDLE, authentication);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aRequestThatIsNoLongerPendingIsLeftToTheStoreAndSendsNoEmail() {
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(user);
        when(connectRequestStore.findPending(HANDLE)).thenReturn(Optional.empty());
        when(connectRequestStore.approve(HANDLE, EMAIL)).thenThrow(new ConnectRequestExpiredException());

        assertThatThrownBy(() -> controller.approve(HANDLE, authentication)).isInstanceOf(ConnectRequestExpiredException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    private void pendingRequestTo(final String redirectUri) {
        when(userService.fetchActiveUserByEmail(EMAIL)).thenReturn(user);
        when(connectRequestStore.findPending(HANDLE)).thenReturn(Optional.of(new ConnectRequest(CLIENT_ID, redirectUri, "state", "query")));
        lenient().when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(RegisteredClient.withId("1")
                .clientId(CLIENT_ID)
                .clientName("Claude")
                .redirectUri(redirectUri)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .build());
    }

}
