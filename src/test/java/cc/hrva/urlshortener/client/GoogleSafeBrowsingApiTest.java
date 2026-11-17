package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.GoogleApiProperties;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.safebrowsing.v5.Safebrowsing;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleSafeBrowsingApiTest {

    private static final String KEY = "test-api-key";
    private static final String PROTOBUF = "application/x-protobuf";
    private static final String PHISHING = "http://testsafebrowsing.appspot.com/s/phishing.html";
    private static final String PHISHING_EXPRESSION = "testsafebrowsing.appspot.com/s/phishing.html";
    private static final String CACHE_FIVE_MINUTES = "120308ac02";
    private static final byte[] PHISHING_ANSWER = HexFormat.of().parseHex("0a310a2c" + hex(PHISHING_EXPRESSION) + "120102" + CACHE_FIVE_MINUTES);
    private static final byte[] CLEAN_ANSWER = HexFormat.of().parseHex(CACHE_FIVE_MINUTES);
    private static final Pattern URLS_PARAMETER = Pattern.compile("(^|[?&])urls=");

    @Test
    void readsTheThreatsOfTheAnswerGoogleGaveInProduction() throws IOException {
        final var transport = transportAnswering(ok(PROTOBUF, PHISHING_ANSWER));

        final var threats = apiOver(transport).threatUrls(List.of(PHISHING));

        assertThat(threats).containsExactly(PHISHING_EXPRESSION);
        final var request = URLDecoder.decode(transport.getLowLevelHttpRequest().getUrl(), StandardCharsets.UTF_8);
        assertThat(request)
                .startsWith("https://safebrowsing.googleapis.com/v5/urls:search?")
                .contains("urls=" + PHISHING)
                .contains("key=" + KEY)
                .doesNotContain("alt=");
    }

    @Test
    void cleanAnswerHasNoThreats() throws IOException {
        assertThat(apiOver(transportAnswering(ok(PROTOBUF, CLEAN_ANSWER))).threatUrls(List.of(PHISHING))).isEmpty();
    }

    @Test
    void emptyAnswerHasNoThreats() throws IOException {
        assertThat(apiOver(transportAnswering(ok(PROTOBUF, new byte[0]))).threatUrls(List.of(PHISHING))).isEmpty();
    }

    @Test
    void contentTypeParametersAreIgnored() throws IOException {
        final var transport = transportAnswering(ok(PROTOBUF + "; charset=binary", PHISHING_ANSWER));

        assertThat(apiOver(transport).threatUrls(List.of(PHISHING))).containsExactly(PHISHING_EXPRESSION);
    }

    @Test
    void readsEveryThreatWithUnpackedTypesAndUnknownFields() throws IOException {
        final var malware = "malware.example/download";
        final var unknownVarint = "3805";
        final var unknownFixed32 = "4d01020304";
        final var unpackedTypes = "10011003";
        final var first = lengthDelimited(hex(malware)) + unpackedTypes + unknownVarint;
        final var second = lengthDelimited(hex(PHISHING_EXPRESSION)) + "120102";
        final var answer = unknownFixed32 + lengthDelimited(first) + CACHE_FIVE_MINUTES + lengthDelimited(second);

        final var threats = apiOver(transportAnswering(ok(PROTOBUF, HexFormat.of().parseHex(answer)))).threatUrls(List.of(PHISHING));

        assertThat(threats).containsExactly(malware, PHISHING_EXPRESSION);
    }

    @Test
    void jsonAnswerIsAFailureAndNeverAnEmptyResult() {
        final var transport = transportAnswering(ok("application/json; charset=UTF-8", "{\"threats\": []}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> apiOver(transport).threatUrls(List.of(PHISHING)))
                .isInstanceOf(IOException.class)
                .hasMessage("Safe Browsing answered with content type application/json; charset=UTF-8");
    }

    @Test
    void answerWithoutContentTypeIsAFailure() {
        final var transport = transportAnswering(ok(null, CLEAN_ANSWER));

        assertThatThrownBy(() -> apiOver(transport).threatUrls(List.of(PHISHING)))
                .isInstanceOf(IOException.class)
                .hasMessage("Safe Browsing answered with content type null");
    }

    @Test
    void cutOffAnswerIsAFailure() {
        final var transport = transportAnswering(ok(PROTOBUF, Arrays.copyOf(PHISHING_ANSWER, 30)));

        assertThatThrownBy(() -> apiOver(transport).threatUrls(List.of(PHISHING)))
                .isInstanceOf(InvalidProtocolBufferException.class);
    }

    @Test
    void otherSuccessStatusIsAFailure() {
        final var transport = transportAnswering(new MockLowLevelHttpResponse().setStatusCode(204).setContentType(PROTOBUF));

        assertThatThrownBy(() -> apiOver(transport).threatUrls(List.of(PHISHING)))
                .isInstanceOf(IOException.class)
                .hasMessage("Safe Browsing answered HTTP 204");
    }

    @Test
    void errorStatusIsAFailure() {
        final var error = """
                {"error": {"code": 400, "message": "Unsupported Output Format", "status": "INVALID_ARGUMENT"}}""";
        final var transport = transportAnswering(new MockLowLevelHttpResponse()
                .setStatusCode(400)
                .setContentType("application/json; charset=UTF-8")
                .setContent(error));

        assertThatThrownBy(() -> apiOver(transport).threatUrls(List.of(PHISHING)))
                .isInstanceOfSatisfying(GoogleJsonResponseException.class, exception -> assertThat(exception.getStatusCode()).isEqualTo(400));
    }

    @Test
    void sendsAtMostFiftyUrlsPerRequestAndCombinesTheThreatsOfEveryAnswer() throws IOException {
        final var transport = new RecordingTransport(request -> request == 1 ? ok(PROTOBUF, PHISHING_ANSWER) : ok(PROTOBUF, CLEAN_ANSWER));
        final var urls = IntStream.range(0, 120).mapToObj(index -> "https://site" + index + ".example/").toList();

        final var threats = apiOver(transport).threatUrls(urls);

        assertThat(threats).containsExactly(PHISHING_EXPRESSION);
        assertThat(transport.requests).extracting(GoogleSafeBrowsingApiTest::urlsIn).containsExactly(50L, 50L, 20L);
    }

    @Test
    void aLookupTooLongForAGetGoesAsAPostWithEveryUrlInTheBody() throws IOException {
        final var transport = new RecordingTransport(request -> ok(PROTOBUF, CLEAN_ANSWER));
        final var urls = IntStream.range(0, 20).mapToObj(index -> "https://site" + index + ".example/" + "a".repeat(2_000)).toList();

        apiOver(transport).threatUrls(urls);

        assertThat(transport.requests).singleElement().satisfies(request -> {
            assertThat(request.getUrl()).doesNotContain("urls=");
            assertThat(request.getFirstHeaderValue("X-HTTP-Method-Override")).isEqualTo("GET");
            assertThat(urlsIn(request)).isEqualTo(20);
        });
    }

    @Test
    void noUrlsSendNoRequest() throws IOException {
        final var transport = new RecordingTransport(request -> ok(PROTOBUF, CLEAN_ANSWER));

        assertThat(apiOver(transport).threatUrls(List.of())).isEmpty();
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void aFailedLaterRequestFailsTheWholeLookup() {
        final var quota = """
                {"error": {"code": 429, "message": "Quota exceeded", "status": "RESOURCE_EXHAUSTED"}}""";
        final var transport = new RecordingTransport(request -> request == 1
                ? ok(PROTOBUF, PHISHING_ANSWER)
                : new MockLowLevelHttpResponse().setStatusCode(429).setContentType("application/json; charset=UTF-8").setContent(quota));
        final var urls = IntStream.range(0, 60).mapToObj(index -> "https://site" + index + ".example/").toList();

        assertThatThrownBy(() -> apiOver(transport).threatUrls(urls))
                .isInstanceOfSatisfying(GoogleJsonResponseException.class, exception -> assertThat(exception.getStatusCode()).isEqualTo(429));
    }

    private static GoogleSafeBrowsingApi apiOver(final MockHttpTransport transport) {
        final var properties = new GoogleApiProperties();
        properties.setKey(KEY);
        final var safebrowsing = new Safebrowsing.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("hrva.cc")
                .build();
        return new GoogleSafeBrowsingApi(properties, safebrowsing);
    }

    private static MockHttpTransport transportAnswering(final MockLowLevelHttpResponse response) {
        return new MockHttpTransport.Builder().setLowLevelHttpResponse(response).build();
    }

    private static MockLowLevelHttpResponse ok(final String contentType, final byte[] body) {
        return new MockLowLevelHttpResponse().setStatusCode(200).setContentType(contentType).setContent(body);
    }

    private static String hex(final String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String lengthDelimited(final String hexBytes) {
        return "0a" + HexFormat.of().toHexDigits((byte) (hexBytes.length() / 2)) + hexBytes;
    }

    private static long urlsIn(final MockLowLevelHttpRequest request) {
        final var sent = request.getUrl() + "&" + uncheckedContentOf(request);
        return URLS_PARAMETER.matcher(sent).results().count();
    }

    private static String uncheckedContentOf(final MockLowLevelHttpRequest request) {
        try {
            return request.getContentAsString();
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingTransport extends MockHttpTransport {

        private final List<MockLowLevelHttpRequest> requests = new ArrayList<>();
        private final IntFunction<MockLowLevelHttpResponse> answers;

        private RecordingTransport(final IntFunction<MockLowLevelHttpResponse> answers) {
            this.answers = answers;
        }

        @Override
        public LowLevelHttpRequest buildRequest(final String method, final String url) {
            final var request = new MockLowLevelHttpRequest(url).setResponse(answers.apply(requests.size() + 1));
            requests.add(request);
            return request;
        }

    }

}
