package cc.hrva.urlshortener.configuration.properties;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("app.github-sign-in")
public class GitHubSignInProperties {

    private String clientId;

    @ToString.Exclude
    private String clientSecret;

    private Set<String> scope = new LinkedHashSet<>();

    private String redirectUri;

}
