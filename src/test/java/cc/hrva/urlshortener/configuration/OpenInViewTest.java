package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.model.Url;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        classes = OpenInViewTest.TestApplication.class,
        properties = {
                "spring.liquibase.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.datasource.url=jdbc:h2:mem:open-in-view;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class OpenInViewTest {

    private static final String OPEN_IN_VIEW_WARNING = "spring.jpa.open-in-view is enabled by default";

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void aWebRequestGetsNoEntityManagerOfItsOwnAndStartupDoesNotWarnAboutIt(final CapturedOutput output) {
        assertThat(applicationContext.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
        assertThat(output).doesNotContain(OPEN_IN_VIEW_WARNING);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    static class TestApplication {
    }

}
