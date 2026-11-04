package cc.hrva.urlshortener.service.jobs;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class JobErrorsTest {

    @Test
    void namesTheExceptionClassAndItsMessage() {
        assertThat(JobErrors.describe(new DataAccessResourceFailureException("Unable to acquire JDBC Connection")))
                .isEqualTo("org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection");
    }

    @Test
    void anExceptionWithoutAMessageIsItsClassName() {
        assertThat(JobErrors.describe(new IllegalStateException())).isEqualTo("java.lang.IllegalStateException");
        assertThat(JobErrors.describe(new IllegalStateException("  "))).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void addressesSecretsAndEmailsAreHidden() {
        final var error = JobErrors.describe(new SQLException("Connection to jdbc:postgresql://app:pa55w0rd@db.internal:5432/hrva refused; "
                + "GET https://api.example.com/v1?key=abc123 failed with token=S3CRET-1 and password: hunter2 for ana.kovac@example.com"));

        assertThat(error)
                .isEqualTo("java.sql.SQLException: Connection to jdbc:[url] refused; GET [url] failed with token=[hidden] and password: [hidden] "
                        + "for a***@example.com")
                .doesNotContain("pa55w0rd", "abc123", "S3CRET-1", "hunter2", "ana.kovac");
    }

    @Test
    void bearerTokensAndQuotedSecretsAreHidden() {
        final var error = JobErrors.describe(new IllegalStateException("Refused with Authorization: Bearer eyJhbGciOiJIUzUxMiJ9.e30.c2ln, "
                + "body {\"api_key\":\"vt-S3CRET\",\"apiKey\": \"cf-S3CRET\",\"name\":\"hrva\"} and secret='s3-S3CRET'"));

        assertThat(error)
                .isEqualTo("java.lang.IllegalStateException: Refused with Authorization: [hidden] [hidden], "
                        + "body {\"api_key\":\"[hidden]\",\"apiKey\": \"[hidden]\",\"name\":\"hrva\"} and secret='[hidden]'")
                .doesNotContain("eyJ", "vt-S3CRET", "cf-S3CRET", "s3-S3CRET");
    }

    @Test
    void aLongMessageIsCutToFiveHundredCharactersOnOneLine() {
        final var error = JobErrors.describe(new IllegalStateException("x".repeat(400) + "\n" + "y".repeat(400)));

        assertThat(error).hasSize(JobErrors.MAX_LENGTH).doesNotContain("\n").startsWith("java.lang.IllegalStateException: xxx").endsWith("...");
    }

}
