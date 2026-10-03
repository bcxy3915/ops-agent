package com.example.opsaiagent.tools;

import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.service.OpsServiceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceDiscoveryTools {

    private final OpsServiceManager serviceManager;

    @Tool(description = "【必须调用】查询指定状态的所有服务。" +
            "当用户问'有哪些服务异常'、'哪些服务是 UP 的'、'有多少服务 DOWN'时必须调用此工具。" +
            "状态可选：UP（健康）、DOWN（异常）、UNKNOWN（未检查）")
    public String listServicesByStatus(
            @ToolParam(description = "服务状态，UP / DOWN / UNKNOWN") String status) {

        String upperStatus = status.toUpperCase();
        List<OpsServiceEntity> list = serviceManager.findByStatus(upperStatus);

        log.info("按状态查询服务: status={}, 结果 {} 个", upperStatus, list.size());

        if (list.isEmpty()) {
            return "没有状态为 " + upperStatus + " 的服务";
        }

        return "状态为 " + upperStatus + " 的服务（共 " + list.size() + " 个）：\n"
                + list.stream()
                .map(s -> "- " + s.getName() + " (" + s.getBaseUrl() + ")")
                .collect(Collectors.joining("\n"));
    }
}