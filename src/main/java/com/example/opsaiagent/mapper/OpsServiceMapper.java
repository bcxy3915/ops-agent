package com.example.opsaiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.entity.OpsServiceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OpsServiceMapper extends BaseMapper<OpsServiceEntity> {

    /**
     * 查询某环境下的所有服务
     */
    @Select("SELECT * FROM ops_service WHERE env = #{env} ORDER BY name")
    List<OpsServiceEntity> selectByEnv(String env);
}