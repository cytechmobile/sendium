package smsgateway.providers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;
import java.util.Optional;

@Singleton
public class JsonMapperCustomizer implements ObjectMapperCustomizer {
    public static ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        MAPPER = objectMapper;
    }

    public static String serialize(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> Optional<T> deserialize(String json, Class<T> cls) {
        try {
            return Optional.ofNullable(MAPPER.readValue(json, cls));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static <T> Optional<T> deserialize(String json, TypeReference<T> cls) {
        try {
            return Optional.ofNullable(MAPPER.readValue(json, cls));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> deepCopy(T t) {
        return deserialize(serialize(t), (Class<T>) t.getClass());
    }
}
