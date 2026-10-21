package com.example.opsaiagent.oncall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.oncall.entity.OncallMemberEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 值班人 Mapper（P1 规格 7）
 */
@Mapper
public interface OncallMemberMapper extends BaseMapper<OncallMemberEntity> {
}
