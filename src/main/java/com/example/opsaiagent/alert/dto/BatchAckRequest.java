package com.example.opsaiagent.alert.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量确认请求
 */
@Data
public class BatchAckRequest {

    @NotEmpty(message = "事件 ID 列表不能为空")
    private List<Long> ids;
}
