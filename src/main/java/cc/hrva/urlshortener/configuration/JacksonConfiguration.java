package cc.hrva.urlshortener.configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer throwableWithoutStackTrace() {
        return builder -> builder.addMixIn(Throwable.class, ThrowableMixin.class);
    }

    @JsonIgnoreProperties({"stackTrace", "cause", "suppressed", "localizedMessage"})
    abstract static class ThrowableMixin {
    }

}
