package cc.hrva.urlshortener.service.jobs;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class JobReasonsTest {

    @Test
    void aDatabaseOutageSaysTheDatabaseWasNotReachable() {
        assertThat(JobReasons.of(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")))).isEqualTo("The database wasn't reachable.");
        assertThat(JobReasons.of(new DataAccessResourceFailureException("I/O error", new SocketTimeoutException("Read timed out"))))
                .isEqualTo("The database wasn't reachable.");
    }

    @Test
    void aRedisConnectionFailureSaysRedisWasNotReachable() {
        assertThat(JobReasons.of(new RedisConnectionFailureException("Unable to connect to Redis", new ConnectException("Connection refused"))))
                .isEqualTo("Redis wasn't reachable.");
        assertThat(JobReasons.of(new IllegalStateException("wrapped", new io.lettuce.core.RedisConnectionException("refused"))))
                .isEqualTo("Redis wasn't reachable.");
    }

    @Test
    void aMailFailureSaysTheEmailServerDidNotTakeTheMessage() {
        assertThat(JobReasons.of(new MailSendException("Mail server connection failed"))).isEqualTo("The email server didn't accept the email.");
    }

    @Test
    void anOutsideServiceThatAnswersWithAnErrorNamesTheStatus() {
        assertThat(JobReasons.of(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null)))
                .isEqualTo("An outside service answered with an error (HTTP 502).");
    }

    @Test
    void anOutsideServiceThatDoesNotAnswerSaysSo() {
        assertThat(JobReasons.of(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))))
                .isEqualTo("An outside service didn't answer.");
        assertThat(JobReasons.of(new UnknownHostException("api.example"))).isEqualTo("An outside service didn't answer.");
        assertThat(JobReasons.of(new HttpTimeoutException("request timed out"))).isEqualTo("An outside service didn't answer.");
    }

    @Test
    void aFailureWithAPlainReasonKeepsIt() {
        assertThat(JobReasons.of(new JobFailedException("Redis wasn't reachable, so today's usage is unknown.")))
                .isEqualTo("Redis wasn't reachable, so today's usage is unknown.");
    }

    @Test
    void anythingElseHasNoReason() {
        assertThat(JobReasons.of(new IllegalStateException("boom"))).isNull();
        assertThat(JobReasons.of(new NullPointerException())).isNull();
    }

}
