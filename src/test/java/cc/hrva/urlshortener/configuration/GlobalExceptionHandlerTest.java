package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void aQueryValueOfTheWrongTypeIsABadRequestNamingTheParameter(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe").param("days", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for days"));

        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void aPathValueOfTheWrongTypeIsABadRequestNamingTheParameter(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for id"));

        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void aMissingRequiredParameterIsABadRequestNamingIt(final CapturedOutput output) throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing parameter days"));

        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void anUnavailableBioHandleIsAConflictWithItsText() throws Exception {
        mockMvc.perform(get("/probe/handle"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This handle is taken. Try another."));
    }

    @Test
    void aMissingBioPageIsNotFoundWithItsText() throws Exception {
        mockMvc.perform(get("/probe/bio-page"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("You don't have a bio page yet."));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/bio-page")
        String probeBioPage() {
            throw new BioPageNotFoundException("You don't have a bio page yet.");
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
