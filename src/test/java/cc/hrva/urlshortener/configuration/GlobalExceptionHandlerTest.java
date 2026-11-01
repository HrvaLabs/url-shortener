package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.BulkBlockIncompleteException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.exception.SettingNotFoundException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aDatabaseThatDoesNotAnswerIsA503WithRetryAfterAndNoInternals(final CapturedOutput output) throws Exception {
        for (final var probe : java.util.List.of("/probe/no-connection", "/probe/socket-timeout", "/probe/statement-timeout", "/probe/rollback-after-outage",
                "/probe/closed-connection")) {
            mockMvc.perform(get(probe))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.detail").value("hrva.cc can't reach its database right now. Try again in a minute."));
        }

        assertThat(output).contains("Database unreachable exception=").doesNotContain("Unhandled").doesNotContain("\tat ")
                .doesNotContain("HikariPool-1").doesNotContain("backend");
    }

    @Test
    void aQueryValueOfTheWrongTypeIsABadRequestNamingTheParameter(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe").param("days", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for days"));

        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void aPathValueOfTheWrongTypeIsABadRequestNamingTheParameter(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value for id"));

        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void aMissingRequiredParameterIsABadRequestNamingIt(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Missing parameter days"));

        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void anUnavailableBioHandleIsAConflictWithItsText() throws Exception {
        mockMvc.perform(get("/probe/handle"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This handle is taken. Try another."));
    }

    @Test
    void aMissingBioPageIsNotFoundWithItsText() throws Exception {
        mockMvc.perform(get("/probe/bio-page"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("You don't have a bio page yet."));
    }

    @Test
    void aBioPageChangedMeanwhileIsAConflictWithItsText() throws Exception {
        mockMvc.perform(get("/probe/bio-page-changed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This page changed. Reload and try again."));
    }

    @Test
    void aFieldValidationErrorIsABadRequestWithTheFieldMapAsTheBody() throws Exception {
        mockMvc.perform(get("/probe/field"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.errors.hostname").value("Enter a domain, like go.example.com"))
                .andExpect(jsonPath("$.detail").value("Enter a domain, like go.example.com"))
                .andExpect(jsonPath("$.hostname").doesNotExist());
    }

    @Test
    void aSettingValueErrorIsABadRequestWithExactlyTheValueMap() throws Exception {
        mockMvc.perform(get("/probe/setting-value"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Use a number from 2 to 50.",
                         "instance": "/probe/setting-value", "errors": {"value": "Use a number from 2 to 50."}}""", JsonCompareMode.STRICT));
    }

    @Test
    void anUnknownSettingIsNotFoundWithItsText() throws Exception {
        mockMvc.perform(get("/probe/setting"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().json("""
                        {"type": "about:blank", "title": "Not Found", "status": 404, "detail": "There is no setting NOPE.",
                         "instance": "/probe/setting"}""", JsonCompareMode.STRICT));
    }

    @Test
    void anUnfinishedBulkBlockIsServiceUnavailableWithItsText() throws Exception {
        mockMvc.perform(get("/probe/bulk-block"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The domains are blocked, but turning off their links failed. Block the same domains again to finish."));
    }

    @Test
    void severalFieldErrorsKeepEveryFieldAndNameTheFirstInTheDetail() throws Exception {
        mockMvc.perform(get("/probe/fields"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.alias").value("Use letters and digits."))
                .andExpect(jsonPath("$.errors.longUrl").value("Enter a URL."))
                .andExpect(jsonPath("$.detail").value("Use letters and digits."));
    }

    @Test
    void aRateLimitAnswersRetryAfterInSeconds() throws Exception {
        mockMvc.perform(get("/probe/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.detail").value("Too many links created in the last hour."));
    }

    @Test
    void aRateLimitWithoutAWindowStillAnswersRetryAfter() throws Exception {
        mockMvc.perform(get("/probe/rate-limit-unknown"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void anUnavailableSignInIsAServiceUnavailableWithRetryAfter() throws Exception {
        mockMvc.perform(get("/probe/sign-in-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.detail").value("Signing in isn't available right now. Try again in a minute."));
    }

    @Test
    void aBusyServiceIsAServiceUnavailableWithItsRetryAfter() throws Exception {
        mockMvc.perform(get("/probe/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value("Link previews are busy. Try again in a few seconds."));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/busy")
        String probeBusy() {
            throw new ServiceBusyException("Link previews are busy. Try again in a few seconds.", java.time.Duration.ofSeconds(5));
        }

        @GetMapping("/probe/sign-in-unavailable")
        String probeSignInUnavailable() {
            throw new SignInUnavailableException("Signing in isn't available right now. Try again in a minute.");
        }

        @GetMapping("/probe/fields")
        String probeFields() {
            final var errors = new java.util.LinkedHashMap<String, String>();
            errors.put("alias", "Use letters and digits.");
            errors.put("longUrl", "Enter a URL.");
            throw new FieldValidationException(errors);
        }

        @GetMapping("/probe/no-connection")
        String probeNoConnection() {
            throw new org.springframework.transaction.CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                    new java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 3000ms."));
        }

        @GetMapping("/probe/socket-timeout")
        String probeSocketTimeout() {
            throw new org.springframework.dao.DataAccessResourceFailureException("An I/O error occurred while sending to the backend.",
                    new java.net.SocketTimeoutException("Read timed out"));
        }

        @GetMapping("/probe/statement-timeout")
        String probeStatementTimeout() {
            throw new org.springframework.dao.QueryTimeoutException("canceling statement due to user request");
        }

        @GetMapping("/probe/rollback-after-outage")
        String probeRollbackAfterOutage() {
            final var rollback = new org.springframework.transaction.TransactionSystemException("Could not roll back JPA transaction");
            rollback.initApplicationException(new org.springframework.dao.DataAccessResourceFailureException("This connection has been closed."));
            throw rollback;
        }

        @GetMapping("/probe/closed-connection")
        String probeClosedConnection() {
            throw new org.springframework.orm.jpa.JpaSystemException(new org.hibernate.TransactionException("Unable to rollback against JDBC Connection",
                    new java.sql.SQLException("Connection is closed")));
        }

        @GetMapping("/probe/rate-limit")
        String probeRateLimit() {
            throw new RateLimitExceededException("Too many links created in the last hour.", java.time.Duration.ofHours(1));
        }

        @GetMapping("/probe/rate-limit-unknown")
        String probeRateLimitUnknown() {
            throw new RateLimitExceededException("Too many tries.");
        }

        @GetMapping("/probe/field")
        String probeField() {
            throw new FieldValidationException(Map.of("hostname", "Enter a domain, like go.example.com"));
        }

        @GetMapping("/probe/setting-value")
        String probeSettingValue() {
            throw new FieldValidationException("value", "Use a number from 2 to 50.");
        }

        @GetMapping("/probe/setting")
        String probeSetting() {
            throw new SettingNotFoundException("There is no setting NOPE.");
        }

        @GetMapping("/probe/bio-page")
        String probeBioPage() {
            throw new BioPageNotFoundException("You don't have a bio page yet.");
        }

        @GetMapping("/probe/bio-page-changed")
        String probeBioPageChanged() {
            throw new BioPageConflictException("This page changed. Reload and try again.");
        }

        @GetMapping("/probe/bulk-block")
        String probeBulkBlock() {
            throw new BulkBlockIncompleteException("The domains are blocked, but turning off their links failed. Block the same domains again to finish.",
                    new IllegalStateException("scan failed"));
        }

        @GetMapping("/probe/handle")
        String probeHandle() {
            throw new BioHandleUnavailableException("This handle is taken. Try another.");
        }

        @GetMapping("/probe")
        String probe(@RequestParam("days") final int days) {
            return String.valueOf(days);
        }

        @GetMapping("/probe/{id}")
        String probeById(@PathVariable("id") final long id) {
            return String.valueOf(id);
        }

    }

}
