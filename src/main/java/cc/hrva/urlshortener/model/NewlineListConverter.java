package cc.hrva.urlshortener.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

@Converter
public class NewlineListConverter implements AttributeConverter<List<String>, String> {

    private static final String SEPARATOR = "\n";

    @Override
    public String convertToDatabaseColumn(final List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(SEPARATOR, values);
    }

    @Override
    public List<String> convertToEntityAttribute(final String column) {
        if (StringUtils.isBlank(column)) {
            return List.of();
        }
        return column.lines().map(String::strip).filter(StringUtils::isNotEmpty).toList();
    }

}
