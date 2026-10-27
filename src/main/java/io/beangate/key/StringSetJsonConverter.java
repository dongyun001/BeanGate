package io.beangate.key;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashSet;
import java.util.Set;

/** Stores a set of strings as a JSON array in a portable TEXT column. */
@Converter
public class StringSetJsonConverter implements AttributeConverter<Set<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashSet<String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Set<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize allowed models", e);
        }
    }

    @Override
    public Set<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot parse allowed models: " + dbData, e);
        }
    }
}
