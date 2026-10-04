package com.example.opsaiagent.sensitive;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

/**
 * 敏感字段序列化器
 * 实现 ContextualSerializer 接口，让 Jackson 在序列化每个字段前
 * 检查该字段的 @Sensitive 注解，拿到具体的脱敏类型。
 */
public class SensitiveSerializer extends JsonSerializer<String>
        implements ContextualSerializer {

    private SensitiveType type;

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        String masked = SensitiveMasker.mask(value, type);
        gen.writeString(masked);
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        if (property == null) {
            return prov.findNullValueSerializer(property);
        }
        Sensitive annotation = property.getAnnotation(Sensitive.class);
        if (annotation == null) {
            annotation = property.getContextAnnotation(Sensitive.class);
        }
        if (annotation != null) {
            SensitiveSerializer serializer = new SensitiveSerializer();
            serializer.type = annotation.value();
            return serializer;
        }
        return prov.findValueSerializer(property.getType(), property);
    }
}