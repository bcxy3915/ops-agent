package com.example.opsaiagent.tools;

import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.ops.service.OpsServiceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceDiscoveryTools {

    private final OpsServiceManager serviceManager;

    /**
     * 按状态查询当前权限范围内的服务。
     * @param status 服务状态
     * @return 匹配的服务列表
     */
    public String listServicesByStatus(String status) {

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