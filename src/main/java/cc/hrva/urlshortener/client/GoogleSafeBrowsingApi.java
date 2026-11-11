package cc.hrva.urlshortener.client;

import cc.hrva.urlshortener.configuration.properties.GoogleApiProperties;
import com.google.api.client.http.HttpMediaType;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.services.safebrowsing.v5.Safebrowsing;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GoogleSafeBrowsingApi {

    private static final String PROTOBUF = "application/x-protobuf";
    private static final int TAG_TYPE_BITS = 3;
    private static final int THREATS_TAG = 1 << TAG_TYPE_BITS | WireFormat.WIRETYPE_LENGTH_DELIMITED;
    private static final int URL_TAG = 1 << TAG_TYPE_BITS | WireFormat.WIRETYPE_LENGTH_DELIMITED;

    private final GoogleApiProperties googleApiProperties;
    private final Safebrowsing safebrowsing;

    public List<String> threatUrls(final List<String> urls) throws IOException {
        final var response = safebrowsing.urls()
                .search()
                .setUrls(urls)
                .setKey(googleApiProperties.getKey())
                .executeUnparsed();
        try {
            if (response.getStatusCode() != HttpStatusCodes.STATUS_CODE_OK) {
                throw new IOException("Safe Browsing answered HTTP " + response.getStatusCode());
            }
            if (response.getContentType() == null || !HttpMediaType.equalsIgnoreParameters(response.getContentType(), PROTOBUF)) {
                throw new IOException("Safe Browsing answered with content type " + response.getContentType());
            }
            final var content = response.getContent();
            return content == null ? List.of() : threatUrlsOf(CodedInputStream.newInstance(content));
        } finally {
            response.disconnect();
        }
    }

    private static List<String> threatUrlsOf(final CodedInputStream answer) throws IOException {
        final var urls = new ArrayList<String>();
        for (var tag = answer.readTag(); tag != 0; tag = answer.readTag()) {
            if (tag == THREATS_TAG) {
                urls.add(urlOf(answer.readBytes().newCodedInput()));
            } else {
                answer.skipField(tag);
            }
        }
        return List.copyOf(urls);
    }

    private static String urlOf(final CodedInputStream threat) throws IOException {
        var url = "";
        for (var tag = threat.readTag(); tag != 0; tag = threat.readTag()) {
            if (tag == URL_TAG) {
                url = threat.readString();
            } else {
                threat.skipField(tag);
            }
        }
        return url;
    }

}
