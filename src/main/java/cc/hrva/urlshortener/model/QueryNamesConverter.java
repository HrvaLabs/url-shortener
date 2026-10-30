package cc.hrva.urlshortener.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

@Converter
public class QueryNamesConverter implements AttributeConverter<List<String>, String> {

    private static final String SEPARATOR = "\n";

    @Override
    public String convertToDatabaseColumn(final List<String> names) {
        return names == null ? "" : String.join(SEPARATOR, names);
    }

    @Override
    public List<String> convertToEntityAttribute(final String column) {
        return StringUtils.isEmpty(column) ? List.of() : List.of(column.split(SEPARATOR));
    }

}
