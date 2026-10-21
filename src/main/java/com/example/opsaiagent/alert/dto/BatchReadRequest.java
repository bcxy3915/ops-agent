package com.example.opsaiagent.alert.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量已读请求（ids 为空时表示标记全部待处理事件已读）
 */
@Data
public class BatchReadRequest {

    /** 要标记已读的事件 ID 列表；为空时对全部待处理事件（FIRING/SILENCED）标记已读 */
    private List<Long> ids;
}
