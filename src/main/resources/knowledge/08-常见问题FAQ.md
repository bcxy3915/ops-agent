# 常见问题 FAQ

## Q1：如何查看服务健康状态？
访问 /actuator/health，返回 UP 表示健康。

## Q2：如何查看服务版本？
访问 /actuator/info。

## Q3：如何查看服务日志？
日志路径：/var/log/{service-name}/app.log

## Q4：如何临时关闭某个接口？
在网关配置中禁用对应路由。

## Q5：如何查看当前 QPS？
访问 /actuator/metrics/http.server.requests，查看 count 字段。

## Q6：如何查看慢请求？
访问 /actuator/metrics/http.server.requests?tag=uri:/orders/slow

## Q7：如何判断是否需要扩容？
CPU > 80% 或内存 > 90% 持续 5 分钟，考虑扩容。

## Q8：如何联系 DBA？
DBA 值班群：xxx，或拨打 xxx。

## Q9：如何申请临时权限？
在权限系统提交申请，需直属领导审批。

## Q10：如何查看历史告警？
登录 Alertmanager，按时间范围筛选。