package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.service.impl.DefaultLinkPreviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLinkPreviewServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://localhost:8080/actuator",
            "http://10.1.2.3/",
            "http://172.16.0.5/",
            "http://192.168.1.1/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[fd00::1]/",
            "http://[fe80::1]/",
            "ftp://1.1.1.1/file",
            "file:///etc/passwd",
            "not a url"
    })
    void refusesTargetsThatAreNotPublicWebAddresses(final String url) {
        assertThat(DefaultLinkPreviewService.isPublicTarget(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://1.1.1.1/", "http://93.184.215.14/page"})
    void allowsPublicAddresses(final String url) {
        assertThat(DefaultLinkPreviewService.isPublicTarget(url)).isTrue();
    }

    @Test
    void returnsAnEmptyPreviewForAnInternalTargetWithoutFetchingIt() {
        assertThat(new DefaultLinkPreviewService().fetchPreview("http://169.254.169.254/latest/meta-data/"))
                .isEqualTo(new LinkPreviewResponse("http://169.254.169.254/latest/meta-data/", null, null, null));
    }

}
