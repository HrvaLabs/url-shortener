package cc.hrva.urlshortener.model;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewlineListConverterTest {

    private final NewlineListConverter converter = new NewlineListConverter();

    @Test
    void urlsAreStoredOnePerLine() {
        assertThat(converter.convertToDatabaseColumn(List.of("https://a.example/list.txt", "https://b.example/list.txt")))
                .isEqualTo("https://a.example/list.txt\nhttps://b.example/list.txt");
    }

    @Test
    void anEmptyListIsStoredAsNull() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void eachNonBlankLineIsReadBackAsOneUrl() {
        assertThat(converter.convertToEntityAttribute("https://a.example/list.txt\r\n\nhttps://b.example/list.txt\n"))
                .containsExactly("https://a.example/list.txt", "https://b.example/list.txt");
    }

    @Test
    void anEmptyColumnIsReadAsNoUrls() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute(" ")).isEmpty();
    }

}
