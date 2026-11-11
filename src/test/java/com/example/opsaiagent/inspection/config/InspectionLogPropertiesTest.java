package com.example.opsaiagent.inspection.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证日志源嵌套配置绑定与参数约束。 */
class InspectionLogPropertiesTest {
    /**
     * 校验固定源定义及默认时区能够正确绑定。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void bindsFixedSourceAndDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "ops-agent.inspection.logs.sources.main.service-id", "mall-id",
                "ops-agent.inspection.logs.sources.main.root", "D:/test-fixtures/logs",
                "ops-agent.inspection.logs.sources.main.file", "mall.log"));
        var result = new Binder(source).bind("ops-agent.inspection.logs",
                Bindable.of(InspectionLogProperties.class)).get();
        assertThat(result.getSources().get("main").getServiceId()).isEqualTo("mall-id");
        assertThat(result.getSources().get("main").getFormat()).isEqualTo("SPRING");
        assertThat(result.getSources().get("main").getZoneId()).isEqualTo("Asia/Shanghai");
    }

    /**
     * 不完整源定义与未知格式必须触发配置校验。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void validatesNestedSource() {
        var properties = new InspectionLogProperties();
        var source = new InspectionLogProperties.Source();
        source.setFormat("SHELL");
        properties.getSources().put("main", source);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(properties);
            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).endsWith("serviceId"));
            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).endsWith("format"));
        }
    }
}
