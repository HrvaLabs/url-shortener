package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.util.DatabaseOutages;
import cc.hrva.urlshortener.util.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.GenericFilterBean;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends GenericFilterBean {

  public static final String AUTHORIZATION_HEADER = "Authorization";
  public static final String SESSION_ENDED = "Your session ended. Sign in again.";
  private static final String PUBLIC_API = "/api/v1/public/";
  private static final String CHANGELOG = "/api/changelog";
  private static final Duration DATABASE_RETRY = Duration.ofMinutes(1);

  private final TokenProvider tokenProvider;
  private final UserRepository userRepository;

  @Override
  public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse, final FilterChain filterChain)
      throws IOException, ServletException {

    final var httpServletRequest = (HttpServletRequest) servletRequest;
    final var httpServletResponse = (HttpServletResponse) servletResponse;

    final var jwt = getToken(httpServletRequest);
    final var token = StringUtils.hasText(jwt) ? tokenProvider.read(jwt) : Optional.<SessionToken>empty();
    if (token.isPresent()) {
      final Optional<User> user;
      try {
        user = activeUser(token.get().authentication().getName());
      } catch (final RuntimeException exception) {
        if (!DatabaseOutages.isOutage(exception)) {
          throw exception;
        }
        log.warn("Session lookup refused, the database isn't answering exception={}", exception.getClass().getName());
        ProblemResponses.retryAfter(httpServletResponse, DATABASE_RETRY);
        ProblemResponses.write(httpServletRequest, httpServletResponse, DatabaseOutages.problem());
        return;
      }
      final var ended = user.isPresent() && sessionEnded(user.get(), token.get());
      if (ended && !isPublic(httpServletRequest)) {
        log.info("Refused a token issued before the account's sessions ended user id={}", user.get().getId());
        ProblemResponses.write(httpServletRequest, httpServletResponse, HttpStatus.UNAUTHORIZED, SESSION_ENDED);
        return;
      }
      if (user.isPresent() && !ended) {
        SecurityContextHolder.getContext().setAuthentication(TokenProvider.authenticationOf(user.get(), jwt));
      }
    }
    filterChain.doFilter(servletRequest, servletResponse);
  }

  private static boolean sessionEnded(final User user, final SessionToken token) {
    return user.needsTokenIssueTime() && user.sessionEndedFor(token.issuedAt());
  }

  private static boolean isPublic(final HttpServletRequest request) {
    final var path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith(PUBLIC_API) || CHANGELOG.equals(path);
  }

  private Optional<User> activeUser(final String email) {
    return userRepository.findByEmail(email)
        .filter(user -> !Boolean.FALSE.equals(user.getActive()));
  }

  private String getToken(final HttpServletRequest request) {
    final var bearerToken = request.getHeader(AUTHORIZATION_HEADER);
    if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
      return bearerToken.substring(7);
    }

    return null;
  }
}
