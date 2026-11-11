package com.example.opsaiagent.inspection.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证服务端探针配置的嵌套绑定与约束。 */
class BusinessProbePropertiesTest {
    /**
     * 验证配置项绑定到探针定义并保留默认阈值。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void bindsRegisteredProbeWithDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "ops-agent.inspection.business.probes.products.service-id", "mall-id",
                "ops-agent.inspection.business.probes.products.path", "/products",
                "ops-agent.inspection.business.probes.products.json-pointer", "/code",
                "ops-agent.inspection.business.probes.products.expected-value", "0"));
        var result = new Binder(source).bind("ops-agent.inspection.business",
                Bindable.of(BusinessProbeProperties.class)).get();
        var probe = result.getProbes().get("products");
        assertThat(probe.getServiceId()).isEqualTo("mall-id");
        assertThat(probe.getJsonPointer()).isEqualTo("/code");
        assertThat(probe.getExpectedValue()).isEqualTo("0");
        assertThat(probe.getExpectedStatus()).isEqualTo(200);
        assertThat(probe.getMaxLatencyMs()).isEqualTo(2000);
    }

    /**
     * 验证约束向嵌套定义传播，避免将错误状态码作为健康预期。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void validatesNestedDefinition() {
        var properties = new BusinessProbeProperties();
        var definition = new BusinessProbeProperties.Definition();
        definition.setExpectedStatus(500);
        properties.getProbes().put("products", definition);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(properties);
            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).endsWith("serviceId"));
            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).endsWith("expectedStatus"));
        }
    }
}
