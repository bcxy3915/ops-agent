package com.example.opsaiagent.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.opsaiagent.entity.OpsServiceEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MybatisFillHandler 单元测试
 * 覆盖：插入时自动填充 registeredAt/updatedAt，更新时自动填充 updatedAt
 */
@DisplayName("MyBatis 字段自动填充")
class MybatisFillHandlerTest {

    private final MybatisFillHandler handler = new MybatisFillHandler();

    @BeforeAll
    static void initTableInfo() {
        // 为实体注册 TableInfo，否则 strictInsertFill/strictUpdateFill 无法识别填充字段
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OpsServiceEntity.class);
    }

    @Test
    @DisplayName("insertFill：填充 registeredAt 与 updatedAt")
    void insertFill_fillsBothFields() {
        OpsServiceEntity entity = new OpsServiceEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        assertThat(entity.getRegisteredAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateFill：只填充 updatedAt")
    void updateFill_fillsUpdatedAt() {
        OpsServiceEntity entity = new OpsServiceEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.updateFill(metaObject);

        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("insertFill：已存在的值不会被覆盖")
    void insertFill_doesNotOverrideExistingValue() {
        OpsServiceEntity entity = new OpsServiceEntity();
        java.time.LocalDateTime fixed = java.time.LocalDateTime.of(2020, 1, 1, 0, 0);
        entity.setRegisteredAt(fixed);
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.insertFill(metaObject);

        // strictInsertFill 只在字段为 null 时填充
        assertThat(entity.getRegisteredAt()).isEqualTo(fixed);
        assertThat(entity.getUpdatedAt()).isNotNull();
    }
}
