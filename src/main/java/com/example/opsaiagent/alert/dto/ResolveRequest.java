package com.example.opsaiagent.alert.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标记恢复请求（规格 2.4）：恢复备注可选，最多 500 字
 */
@Data
public class ResolveRequest {

    /** 恢复备注（可选） */
    @Size(max = 500, message = "恢复备注最多 500 字")
    private String resolutionNote;
}
