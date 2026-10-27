package cc.hrva.urlshortener.configuration;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import java.util.Locale;

public class LowerCaseClassNameResolver extends TypeIdResolverBase {

    @Override
    public String idFromValue(final Object value) {
        return value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    @Override
    public String idFromValueAndType(final Object value, final Class<?> suggestedType) {
        return idFromValue(value);
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        return JsonTypeInfo.Id.CUSTOM;
    }
}
