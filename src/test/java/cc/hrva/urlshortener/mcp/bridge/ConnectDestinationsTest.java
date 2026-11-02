package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectDestinationsTest {

    private final ConnectDestinations destinations = new ConnectDestinations(withKnownApps(
            app(List.of("Claude", "Anthropic"), List.of("claude.ai", "claude.com", "anthropic.com")),
            app(List.of("ChatGPT", "OpenAI"), List.of("chatgpt.com", "openai.com")),
            app(List.of("VS Code", "VSCode", "Visual Studio Code"), List.of("visualstudio.com", "vscode.dev"))));

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "Claude | https://claude.ai/api/mcp/auth_callback | claude.ai | false | false",
            "Claude | https://www.Claude.com/cb | www.claude.com | false | false",
            "claude | https://evil.example/cb | evil.example | false | true",
            "Claude (official) | https://claude.ai.evil.example/cb | claude.ai.evil.example | false | true",
            "My Clàude helper | https://evil.example/cb | evil.example | false | true",
            "ChatGPT | https://chatgpt.com/connector/oauth/x | chatgpt.com | false | false",
            "ChatGPT | https://claude.ai/cb | claude.ai | false | true",
            "Claude and ChatGPT | https://claude.ai/cb | claude.ai | false | true",
            "Visual Studio Code | https://vscode.dev/redirect | vscode.dev | false | false",
            "VS-Code | https://evil.example/cb | evil.example | false | true",
            "Claudette | https://evil.example/cb | evil.example | false | false",
            "Link helper | https://evil.example/cb | evil.example | false | false",
            "Claude Desktop | http://localhost:53682/callback | localhost | true | false",
            "Claude Desktop | http://127.0.0.1:6274/oauth/callback | 127.0.0.1 | true | false",
            "Claude Desktop | http://[::1]:6274/cb | [::1] | true | false",
            "Claude Desktop | http://app.localhost/cb | app.localhost | true | false"
    })
    void theViewSaysWhereTheAppSendsYouAndWhetherItsNameClaimsAnotherAppsName(final String clientName, final String redirectUri,
                                                                               final String host, final boolean loopback, final boolean lookalike) {
        assertThat(destinations.viewOf(clientName, redirectUri)).isEqualTo(new ConnectRequestView(clientName, host, loopback, lookalike, null, false));
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "Cursor | cursor://anysphere.cursor-retrieval/oauth/callback | cursor",
            "Claude | Claude-Desktop://callback | claude-desktop",
            "Claude | com.example.app:/oauth | com.example.app"
    })
    void aCustomSchemeIsShownAsAnAppOnThisDeviceThatOpensItsLinks(final String clientName, final String redirectUri, final String scheme) {
        assertThat(destinations.viewOf(clientName, redirectUri)).isEqualTo(new ConnectRequestView(clientName, null, false, false, scheme, false));
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "Claude | not a uri",
            "Claude | /relative/callback",
            "Claude | https:///no-host",
            "Claude | http:callback"
    })
    void aRedirectWithoutAHostOrThatDoesNotParseIsUnknownNotThisDevice(final String clientName, final String redirectUri) {
        assertThat(destinations.viewOf(clientName, redirectUri)).isEqualTo(new ConnectRequestView(clientName, null, false, false, null, true));
    }

    private static AppProperties withKnownApps(final AppProperties.KnownApp... apps) {
        final var appProperties = new AppProperties();
        appProperties.setMcp(new AppProperties.Mcp());
        appProperties.getMcp().setKnownApps(List.of(apps));
        return appProperties;
    }

    private static AppProperties.KnownApp app(final List<String> names, final List<String> domains) {
        final var app = new AppProperties.KnownApp();
        app.setNames(names);
        app.setDomains(domains);
        return app;
    }

}
