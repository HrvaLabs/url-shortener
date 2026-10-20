package cc.hrva.urlshortener.mcp.authorization;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.web.AuthenticationEntryPoint;

public class InvalidClientEntryPoint implements AuthenticationEntryPoint {

    private static final OAuth2Error INVALID_CLIENT = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_CLIENT,
            "Client authentication failed",
            "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2"
    );

    private final HttpMessageConverter<OAuth2Error> errorResponseConverter = new OAuth2ErrorHttpMessageConverter();

    @Override
    public void commence(final HttpServletRequest request,
                         final HttpServletResponse response,
                         final AuthenticationException authenticationException) throws IOException {
        final var httpResponse = new ServletServerHttpResponse(response);
        httpResponse.setStatusCode(HttpStatus.UNAUTHORIZED);
        errorResponseConverter.write(INVALID_CLIENT, null, httpResponse);
    }

}
