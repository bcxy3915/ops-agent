package com.example.opsaiagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 知识文档编辑请求
 */
@Data
public class KnowledgeDocumentUpdateRequest {

    /** 标题 */
    @NotBlank(message = "文档标题不能为空")
    @Size(max = 255, message = "文档标题过长")
    private String title;

    /** 负责人 */
    @Size(max = 64, message = "负责人名称过长")
    private String owner;

    /** 适用服务列表 */
    private List<String> serviceIds;

    /** 标签 */
    private List<String> tags;

    /** 文档内容（重新分块时使用；为空则仅更新元数据） */
    private String rawContent;
}
