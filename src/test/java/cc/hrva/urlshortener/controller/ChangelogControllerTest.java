package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ChangelogControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ChangelogController())
            .build();

    @Test
    void aMissingChangelogIsA500Problem() throws Exception {
        MockMvcBuilders.standaloneSetup(new ChangelogController(new ClassPathResource("no-such-changelog.json")))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(get("/api/changelog"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void shouldReturnChangelog() throws Exception {
        mockMvc.perform(get("/api/changelog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releases").isArray())
                .andExpect(jsonPath("$.releases[0].version").isString());
    }

}
