package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.GenericFilterBean;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends GenericFilterBean {

  public static final String AUTHORIZATION_HEADER = "Authorization";
  public static final String SESSION_ENDED = "Your session ended. Sign in again.";
  private static final String PUBLIC_API = "/api/v1/public/";
  private static final String CHANGELOG = "/api/changelog";

  private final TokenProvider tokenProvider;
  private final UserRepository userRepository;
  private final JsonMapper jsonMapper;

  @Override
  public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse, final FilterChain filterChain)
      throws IOException, ServletException {

    final var httpServletRequest = (HttpServletRequest) servletRequest;
    final var httpServletResponse = (HttpServletResponse) servletResponse;

    final var jwt = getToken(httpServletRequest);
    if (StringUtils.hasText(jwt) && this.tokenProvider.validateToken(jwt)) {
      final var authentication = this.tokenProvider.getAuthentication(jwt);
      final var user = activeUser(authentication.getName());
      final var ended = user.isPresent() && sessionEnded(user.get(), jwt);
      if (ended && !isPublic(httpServletRequest)) {
        log.info("Refused a token issued before the account's sessions ended user id={}", user.get().getId());
        writeSessionEnded(httpServletResponse);
        return;
      }
      if (user.isPresent() && !ended) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }
    filterChain.doFilter(servletRequest, servletResponse);
  }

  private boolean sessionEnded(final User user, final String jwt) {
    return user.needsTokenIssueTime() && user.sessionEndedFor(tokenProvider.issuedAt(jwt).orElse(null));
  }

  private static boolean isPublic(final HttpServletRequest request) {
    final var path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith(PUBLIC_API) || CHANGELOG.equals(path);
  }

  private Optional<User> activeUser(final String email) {
    return userRepository.findByEmail(email)
        .filter(user -> !Boolean.FALSE.equals(user.getActive()));
  }

  private void writeSessionEnded(final HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(jsonMapper.writeValueAsString(Map.of("error", SESSION_ENDED)));
  }

  private String getToken(final HttpServletRequest request) {
    final var bearerToken = request.getHeader(AUTHORIZATION_HEADER);
    if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
      return bearerToken.substring(7);
    }

    return null;
  }
}
