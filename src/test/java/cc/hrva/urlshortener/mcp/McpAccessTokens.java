package cc.hrva.urlshortener.mcp;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class McpAccessTokens {

    private static final String REDIRECT_URI = "http://localhost:53682/callback";
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String REGISTRATION = """
            {"redirect_uris":["http://localhost:53682/callback"],"token_endpoint_auth_method":"none"}""";

    private final MockMvc mockMvc;

    McpAccessTokens(final MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    String issue(final String email, final String resource) throws Exception {
        final var clientId = register();
        final var code = authorize(clientId, email, resource);

        final var body = mockMvc.perform(post("/oauth2/token")
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", clientId)
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", CODE_VERIFIER)
                        .param("resource", resource))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.access_token");
    }

    private String register() throws Exception {
        final var body = mockMvc.perform(post("/oauth2/register")
                        .contentType(APPLICATION_JSON)
                        .content(REGISTRATION))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.client_id");
    }

    private String authorize(final String clientId, final String email, final String resource) throws Exception {
        final var location = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("code_challenge", s256(CODE_VERIFIER))
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("resource", resource)
                        .queryParam("state", "state")
                        .session(signedInSession(email)))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
    }

    private static MockHttpSession signedInSession(final String email) {
        final var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        final var principal = new User(email, "", authorities);
        final var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        final var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

        return session;
    }

    private static String s256(final String verifier) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
