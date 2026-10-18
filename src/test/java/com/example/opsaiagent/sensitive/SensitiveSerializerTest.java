package com.example.opsaiagent.sensitive;

import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SensitiveSerializer 单元测试
 * 覆盖：Jackson 序列化时按 @Sensitive 注解脱敏 / ContextualSerializer 分支
 */
@DisplayName("敏感字段序列化")
class SensitiveSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class UserDTO {
        @Sensitive(SensitiveType.PHONE)
        public String phone;

        @Sensitive(SensitiveType.PASSWORD)
        public String password;

        public String plain;
    }

    @Test
    @DisplayName("serialize：带注解字段序列化时自动脱敏")
    void serialize_masksAnnotatedFields() throws Exception {
        UserDTO dto = new UserDTO();
        dto.phone = "13812345678";
        dto.password = "secret123";
        dto.plain = "hello";

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"phone\":\"138****5678\"");
        assertThat(json).contains("\"password\":\"******\"");
        assertThat(json).contains("\"plain\":\"hello\"");
    }

    @Test
    @DisplayName("createContextual：property 为 null 时返回 null 值序列化器")
    void createContextual_nullProperty() throws Exception {
        SensitiveSerializer serializer = new SensitiveSerializer();
        SerializerProvider provider = mock(SerializerProvider.class);
        JsonSerializer<Object> nullSerializer = mock(JsonSerializer.class);
        when(provider.findNullValueSerializer(null)).thenReturn(nullSerializer);

        JsonSerializer<?> result = serializer.createContextual(provider, null);

        assertThat(result).isSameAs(nullSerializer);
        verify(provider).findNullValueSerializer(null);
    }

    @Test
    @DisplayName("createContextual：字段无注解时返回普通序列化器")
    void createContextual_noAnnotation() throws Exception {
        SensitiveSerializer serializer = new SensitiveSerializer();
        SerializerProvider provider = mock(SerializerProvider.class);
        BeanProperty property = mock(BeanProperty.class);
        com.fasterxml.jackson.databind.JavaType type = mock(com.fasterxml.jackson.databind.JavaType.class);
        JsonSerializer<Object> fallback = mock(JsonSerializer.class);
        when(property.getAnnotation(Sensitive.class)).thenReturn(null);
        when(property.getContextAnnotation(Sensitive.class)).thenReturn(null);
        when(property.getType()).thenReturn(type);
        when(provider.findValueSerializer(type, property)).thenReturn(fallback);

        JsonSerializer<?> result = serializer.createContextual(provider, property);

        assertThat(result).isSameAs(fallback);
        verify(provider).findValueSerializer(type, property);
    }
}
