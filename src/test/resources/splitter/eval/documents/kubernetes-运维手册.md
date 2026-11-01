# Kubernetes 生产集群日常运维手册

**文档编号**：OPS-K8S-001
**版本号**：V1.6
**密级**：内部公开
**生效日期**：2025-03-01
**编制人**：容器平台运维组 张伟
**审核人**：基础架构部 李强
**适用范围**：本文档适用于公司生产环境 Kubernetes 1.26 及以上版本的共享集群与业务专用集群的日常运维工作，覆盖 Pod 故障诊断、工作负载稳定性与连锁故障防护、集群 DNS 解析优化、调度与资源管理、集群版本升级五大领域。生产集群值班人员、应用负责人以及平台工程人员在执行涉及上述领域的操作之前，应完整阅读对应章节，并在规定的变更窗口内按流程执行。需要特别说明的是，本文档不覆盖节点级故障处置与节点维护操作（如节点隔离与腾空的详细步骤），该部分内容由 OPS-K8S-002《Kubernetes 节点维护手册》单独约定，两份手册需配套使用。

## 修订记录

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------------|----------------------------------------|--------|
| V1.0 | 2024-06-10 | 初稿发布，覆盖 Pod 排障与滚动更新流程 | 张伟 |
| V1.2 | 2024-09-15 | 补充调度资源管理与 HPA 弹性伸缩章节 | 王磊 |
| V1.4 | 2024-12-08 | 引入 NodeLocal DNSCache 部署规范与 DNS 治理经验 | 王磊 |
| V1.6 | 2025-03-01 | 重写连锁故障防护章节，细化集群升级前置评估与回退要求 | 张伟 |

## 术语与缩写

| 术语/缩写 | 全称 | 说明 |
|-----------|------|------|
| Pod | - | K8s 最小调度单元，包含一个或多个共享网络与存储的容器 |
| QPS | Queries Per Second | 每秒查询数，本文中特指 DNS 查询频率 |
| PDB | PodDisruptionBudget | 中断预算对象，约束自愿中断期间的最小可用副本数 |
| HPA | Horizontal Pod Autoscaler | 基于指标的水平自动伸缩控制器 |
| VPA | Vertical Pod Autoscaler | 垂直自动伸缩控制器，用于 requests 推荐值计算 |
| API Server | kube-apiserver | 集群控制面入口组件 |
| 5s 超时问题 | conntrack race | UDP 源端口冲突导致的 DNS 偶发 5 秒超时现象 |
| 中断预算 | Disruption Budget | 见 PDB |

## 1. 总则

### 1.1 目的与定位

公司生产环境目前运行三个规模的 Kubernetes 集群，承载订单、支付、履约等三百余个微服务，集群稳定性直接决定核心交易链路的可用性。本文档沉淀容器平台组两年多来在生产运维过程中积累的故障处置经验与配置基线，目的在于让值班工程师在接到告警后能够按照统一的判读顺序快速收敛问题，避免因个人经验差异导致的处置遗漏。文档中给出的阈值、命令与参数均为生产验证过的推荐值，特殊业务场景可在评审后偏离基线，但必须在应用 Wiki 上登记偏离原因与回归计划。凡本文档未覆盖的场景，值班人员应先按第 7 章的升级路径上报，再决定是否自行尝试，禁止在生产集群执行文档未记载的破坏性操作。

### 1.2 值班职责与操作纪律

生产集群实行七乘二十四小时值班制，值班工程师对告警响应时限的要求是 P1 级别五分钟内响应、三十分钟内给出初步结论。所有对生产集群的变更操作必须遵循三条纪律：第一条是任何写操作在执行前必须先通过 `kubectl diff` 或 `--dry-run=client` 预演，确认变更内容与预期一致；第二条是涉及工作负载规格调整（镜像、环境变量、资源配额）的操作必须走发布系统留痕，禁止直接 kubectl edit；第三条是处置过程中产生的关键证据（describe 输出、日志片段、事件列表）必须实时粘贴到事故群，便于复盘追溯。违反操作纪律造成的事故将纳入个人季度考核，这一点在入职培训时已向全员宣贯。

### 1.3 与兄弟文档的边界

为避免内容冲突，本文档只覆盖集群与工作负载层面的运维，不展开节点层面的维护操作。具体来说，节点硬件故障、内核参数调整、容器运行时升级、节点隔离与腾空的标准动作均归属 OPS-K8S-002 手册。本文档在滚动更新与升级章节会引用节点维护的编排顺序，但执行细节请以节点维护手册为准。这样拆分的原因是节点操作与集群操作往往由不同批次的值班人员执行，混在同一文档中容易导致误操作越权。

## 2. Pod 故障诊断

### 2.1 常见异常状态判读

值班人员接到业务方反馈或监控告警后，第一步永远是通过 `kubectl get pods -n <namespace>` 观察目标 Pod 的 STATUS 列。生产环境高频出现的四种异常状态是 Pending、CrashLoopBackOff、ImagePullBackOff 与 Evicted，它们对应完全不同的故障域与处置动作。Pending 表示调度器无法为 Pod 找到合适节点，CrashLoopBackOff 表示容器进程反复崩溃退出，镜像拉取失败状态表示节点无法获取镜像，Evicted 则表示 Pod 因节点资源压力被驱逐。仅凭 STATUS 列只能判断方向，真正的定位信息在 `kubectl describe pod <pod-name>` 输出的 Events 段中，Events 会记录调度决策原因、镜像拉取错误、探针失败等关键事件，值班人员应把 describe 输出完整读一遍再动手，而不是只看第一行报错。

Pending 状态的根因集中在四类：第一类是资源不足，Events 中会出现 Insufficient cpu 或 Insufficient memory，说明所有节点上可分配的余量都不满足 Pod 的 requests；第二类是节点选择器不匹配，包括 nodeSelector、affinity 写错或目标节点带 taint 但 Pod 未配置 toleration；第三类是 PVC 未绑定，Pod 等待卷挂载；第四类是调度上限保护，如单节点 max-pods 耗尽。判读经验是先看 Events 中最后一条 Warning，超过八成的 Pending 案例由资源不足与 taint 问题构成。对于批量扩容场景出现的 Pending，还要结合 `kubectl describe node` 的 Allocated resources 段确认是否是集群整体水位问题，必要时先缩容低优先级业务再扩容核心服务。

CrashLoopBackOff 的处置核心是拿到上一次崩溃的日志，因为 Pod 处于 BackOff 等待期时当前容器可能已经退出，直接 `kubectl logs` 会报容器不存在，必须加 `--previous` 参数读取上一次容器的日志。生产上常见的崩溃原因有三类：应用配置错误导致启动即退出（如数据库连接串写错、必填环境变量缺失）、依赖服务在启动阶段不可达导致初始化失败、以及容器内应用以非预期用户启动导致文件权限拒绝。若 `kubectl logs --previous` 的输出为空，说明应用在打日志之前就退出了，此时应检查镜像的 ENTRYPOINT 与命令参数是否正确拼接，必要时用 2.3 节的临时调试容器进入现场。

### 2.2 镜像拉取失败处置

ImagePullBackOff 是生产环境出现频率最高、但处置成本最低的一类故障，它的完整演进链路是节点先报 ErrImagePull（立即失败），随后 kubelet 进入指数退避重试，状态栏转为镜像拉取失败退避状态，退避间隔从十秒逐步拉长到五分钟。公司镜像仓库为私有仓库 registry.example.com，绝大多数拉取失败由三个要素之一导致：镜像名称或标签拼写错误、目标标签从未推送过（流水线构建失败但部署单照常发出）、以及节点访问仓库的凭证未正确下发。判读时先执行 `kubectl describe pod` 找到 Events 中的具体报错串，报错串会明确指向不同根因：`manifest unknown` 表示标签不存在，`unauthorized: authentication required` 表示凭证问题，`i/o timeout` 表示节点到仓库的网络不通。

确认是凭证问题后，处理动作围绕 imagePullSecrets 展开。这里有一个极易踩坑的前提：该字段引用的 Secret 必须与 Pod 在同一个命名空间，跨命名空间引用会静默失败并直接表现为镜像拉取失败状态。标准做法是为每个业务命名空间创建拉取凭证，命令为 `kubectl create secret docker-registry regcred --docker-server=registry.example.com --docker-username=<用户> --docker-password=<密码> -n <namespace>`，随后在部署清单的 spec.imagePullSecrets 字段引用 regcred。更进一步，生产集群统一将凭证绑定到各命名空间的 default ServiceAccount 上，这样普通业务方无需在自己的清单里声明拉取凭证字段也能正常拉取，凭证轮换时平台组只需更新 Secret 一处。仓库侧每月执行一次密码轮换，轮换后两个小时内若业务出现批量镜像拉取失败，应优先怀疑旧凭证缓存未失效，处置办法是在集群内重建对应 Secret。

镜像仓库出网限速是另一类隐蔽根因。个别业务镜像超过两 GB，批量扩容五十个副本时节点同时拉取，触发仓库的并发限流策略，报错串表现为 `toomanyrequests: You have reached your pull rate limit`。对于大镜像批量发布场景，规范做法是提前用 P2P 镜像加速组件预热，或错峰分批扩容。值班人员遇到全集群多业务同时出现镜像拉取失败时，应第一时间怀疑仓库侧故障而不是逐个排查业务，此时去仓库监控面板确认 QPS 与错误率曲线，能在一分钟内确认根因范围。

### 2.3 日志获取与容器调试

进入容器内部排查是运维的常规手段，但公司生产镜像基于 distroless 构建，多数业务容器内没有 sh 或 bash，`kubectl exec -it <pod> -- /bin/sh` 会直接报 executable file not found。对于这类无 shell 镜像，正确的调试方式是 `kubectl debug -it <pod-name> --image=registry.example.com/infra/busybox:1.36 --target=<container> -- sh`，它会向目标 Pod 注入一个临时容器并共享目标容器的进程命名空间，临时容器内可以 ps 看到业务进程、用 cat 读取应用配置文件、通过回环地址访问业务端口。临时容器不会导致 Pod 重启，也不会影响业务流量，排查结束后退出 shell 即可，K8s 会在 Pod 生命周期结束后清理它。需要强调的是，kubectl debug 产生的调试容器会出现在 describe 的 ephemeralcontainers 列表中，事故复盘时应把这段信息一并留档。

日志获取方面，标准命令是 `kubectl logs -f <pod-name> -c <container> --tail=200 --timestamps`，多容器 Pod 必须显式指定 -c 参数，否则会报 multiple containers 错误。对于 Deployment 场景，直接 `kubectl logs deploy/<name>` 会选取一个存活副本，排查随机性问题时建议先 `kubectl get pods -l app=<name> -o wide` 拿到具体 Pod 列表再逐一取日志。若需要把容器内业务文件（如线程 dump、堆 dump）取出分析，使用 `kubectl cp <namespace>/<pod>:/path/to/file ./local-file`，注意 containerd 运行时场景下 cp 依赖节点上的 tar 二进制，个别精简镜像缺失 tar 时会报 EOF，此时可改用临时容器复制到 emptyDir 再导出的方式绕过。

### 2.4 探针失效场景

探针配置失真导致的问题往往表现为业务可用性下降但监控无明显异常，这类问题在年度复盘中占比不小。三类探针的分工必须严格区分：startupProbe 只在启动阶段生效，用于保护慢启动应用不被过早重启；readinessProbe 失败仅将 Pod 从 Service 端点摘除，流量不再进入但不重启容器；livenessProbe 失败会触发容器重启。探针失效的常见配置问题包括探测超时时间 initialDelaySeconds 估得太短导致应用启动期被误杀、timeoutSeconds 设为一秒但应用健康接口偶发超过一秒导致随机摘除、以及探测端口写成了容器端口对应的宿主机端口导致永远探测失败。值班人员在处理"服务半死不活"类工单时，应当把探针三件套的配置与 describe 输出的探针失败事件对照检查，确认是业务故障还是探针误判。

## 3. 工作负载稳定性与雪崩防护

### 3.1 级联失效与连环重启的形成机理

二四年第四季度订单服务的一次典型事故值得每位值班人员研究。该服务的 livenessProbe 中错误地把 Redis 连通性检查纳入了探测路径，当晚 Redis 主从切换抖动十五秒，结果八十二个订单 Pod 在同一时间窗口内探测失败并触发容器重启，监控曲线上表现为重启计数瞬间竖起一根针，这就是典型的连环重启。更严重的是次生效应：重启风暴期间存活副本数骤降，未重启的 Pod 承接了全部流量，线程池与连接池被打满，接口超时率飙升，上游服务因超时熔断又把流量重试打到订单服务的其余副本上，进一步放大负载，最终从单一依赖抖动演变为整个服务的雪崩。事后统计，从 Redis 抖动到订单服务完全不可用只用了四分钟，而恢复花了四十分钟，因为重启后的 Pod 需要重新预热缓存才能通过就绪检查。

从机理上拆解这个链条：liveness 探测失败触发的是容器级别的重启动作，重启期间该 Pod 的全部在途请求立即失败，客户端按默认重试策略把失败请求重新打到其它副本，使剩余副本在原本就异常的负载上再叠加一层重试洪峰。如果应用的重试参数没有做指数退避与抖动处理，重试洪峰会以固定间隔反复冲击，最终连探针配置正确的副本也会被压垮。因此稳定性防护的本质是切断"依赖抖动 → 探针误杀 → 重启风暴 → 重试洪峰"这四个环节中的任意一环，下一节的配置红线就是按这个链条设计的。值班人员日常应关注 Grafana 上 restarts 指标的突增告警，任何一个 Deployment 在十分钟内重启超过三个副本就应介入排查，这是反复重启的早期信号。

### 3.2 探针配置红线

基于上述机理，平台组在发布准入检查中固化了三条探针红线。第一条红线是 livenessProbe 只允许检查进程自身的健康状态，禁止引入任何外部依赖检查，数据库、缓存、下游服务的连通性一律放到 readinessProbe 中，这条红线保证依赖抖动最多导致 Pod 摘除（保底还有副本接流量），而绝不会触发重启风暴。第二条红线涉及失败容忍度参数：readiness 的 failureThreshold 建议设为三、periodSeconds 十秒，即依赖异常持续三十秒才摘除，给瞬时抖动留缓冲；liveness 的 failureThreshold 建议不低于三，且探针超时不得低于一秒。第三条红线要求所有慢启动应用（JVM 老应用、模型加载类服务）必须配置 startupProbe 并把 initialDelaySeconds 的估算责任交给它，startup 的失败阈值可以放宽到十二次、周期十秒，即允许应用有最长两分钟的安全启动期，期间 liveness 完全不生效，这避免了当年双十零点发布时网关服务因预热慢被反复重启的悲剧重演。

### 3.3 滚动更新与回滚

滚动更新参数是发布安全性的第一道闸门，平台基线要求所有生产 Deployment 显式声明 strategy 字段并采用 `maxSurge: 25%` 与 `maxUnavailable: 0` 的组合。maxUnavailable 为零意味着更新全程副本数只增不减，新 Pod 起一个、旧 Pod 停一个，配合 `minReadySeconds: 30` 的要求——新副本必须连续就绪三十秒才允许继续下一批替换——能有效把未预热副本接流量的风险挡在门外。`progressDeadlineSeconds: 600` 则是自动熔断线，发布卡住超过十分钟 Kubernetes 会标记 Rollout stalled 并在监控系统触发 P2 告警，值班人员应立即执行 `kubectl rollout status deploy/<name>` 确认卡住批次，然后决定是暂停修复还是回滚。终止行为同样要纳入管控：应用收不到优雅下线机会会直接丢消息，因此平台要求所有 Deployment 设置 terminationGracePeriodSeconds 为六十秒，并在 preStop 钩子中 sleep 十秒等待端点表在全集群收敛，再让应用进入关停流程。

回滚操作的前提是发布系统保留了历史 ReplicaSet。公司发布平台默认保留十个版本，回滚命令为 `kubectl rollout undo deploy/<name> --to-revision=<序号>`，序号可通过 `kubectl rollout history deploy/<name>` 查询。需要注意的是，回滚同样会触发滚动替换，maxSurge 与 maxUnavailable 的约束依然生效，因此回滚并不比正常发布更快，遇到配置类故障（如环境变量写错）时，先评估是否能用热修复方式（修改 ConfigMap 后重启单副本验证）缩小影响面。数据面变更（数据库 schema、消息队列结构）永远不能依赖 rollout undo 回滚，这类变更必须在发布前通过反向脚本评审。

### 3.4 中断预算保护

中断预算对象 PDB 通过 `minAvailable: 2` 或 `maxUnavailable: 1` 的声明，向集群承诺该工作负载在任何自愿中断场景下必须保留的最小副本数。当节点维护或自动缩容等操作需要驱逐 Pod 时，控制器会检查驱逐动作是否违反 PDB，违反则驱逐请求进入等待，直到副本数恢复到预算线以上。平台基线规定所有核心链路服务必须配置 PDB 且 minAvailable 不得低于两个副本，HPA 最小副本数也应设置为 minAvailable 的两倍，否则缩容到下限后预算即失效。这一小节的详细驱逐执行流程归节点维护手册管辖，本文档只强调应用侧的责任：PDB 配置遗漏或副本数常年压线，会使任何一次节点维护都变成业务事故。

## 4. 集群 DNS 解析优化

### 4.1 CoreDNS 查询链路与瓶颈

集群内 DNS 解析链路是：Pod 内的 /etc/resolv.conf 指向 kube-dns 的 Service ClusterIP，该 Service 后端是 CoreDNS 的两个副本，CoreDNS 内部通过 kubernetes 插件对接 API Server 实现服务名解析，非集群域名则经 forward 插件转给上游 VPC DNS。这条链路在集群规模增长到四千 Pod 之后开始暴露瓶颈：其一，CoreDNS 副本数固定为二，QPS 高峰时段 CPU 趋近百分之九十，解析延迟抖动明显；其二，Conntrack 表中 UDP 源端口的资源竞争会偶发导致 DNS 查询五秒整超时，也就是社区知名的 conntrack race 问题，应用日志中表现为周期性的偶发超时且难以复现，这是二四年应用侧投诉最集中的问题。识别特征很明确：超时时长恒定为五秒、并发越高概率越大、tcpdump 抓包能看到源端口冲突重传。

### 4.2 ndots 搜索域问题

第二类瓶颈的根源藏在每个 Pod 的 resolv.conf 里。默认注入的配置包含 `search <ns>.svc.cluster.local svc.cluster.local cluster.local` 与 `options ndots:5`，其含义是：任何包含点数少于五的域名，都会先与各搜索域拼接成 FQDN 逐个尝试解析，全部失败后才按原样向上游查询。以应用访问外部域名 www.example.com（一个点）为例，解析器会依次尝试 www.example.com.<ns>.svc.cluster.local、www.example.com.svc.cluster.local、www.example.com.cluster.local 三个无效拼接，加上最终查询共产生四次上游查询，无效查询放大了三倍，集群内每秒数万次的业务外呼解析被放大成十几万次 CoreDNS QPS。更不利的是每次无效拼接查询都要走一遍 conntrack，进一步加剧 4.1 节的五秒超时问题。治理路径有两个：对确定使用完整域名的应用，把 dnsConfig.options 的 ndots 调整为二以跳过大部分拼接；对整个集群，则应实施 4.3 节的本地缓存方案，将放大后的查询拦截在节点本地。

### 4.3 NodeLocal DNSCache 部署与验证

NodeLocal DNSCache 的部署前提需要逐项确认：集群版本不低于 1.21（本集群 1.26 满足）；节点上有余量承载一个约 100m CPU 与 70Mi 内存的 DaemonSet Pod；网络方案允许使用 link-local 地址 169.254.20.10；以及监控面板已为 DNS 指标预留看板。方案的核心是每个节点运行一个缓存型 DNS Pod，监听 169.254.20.10 的五十三端口，各业务 Pod 的 resolv.conf 被改写为指向该地址，集群内域名查询经节点本地缓存命中后直接返回，未命中或集群外域名再转给 CoreDNS，既削减了 CoreDNS 的 QPS 压力，也把 UDP 查询限制在节点内部从而绕开 conntrack 竞争，五秒超时问题随之消失。平台组按官方清单改造并下发 DaemonSet 后，还需将 kube-system 中 CoreDNS 的 Corefile 更新，把 forward 指向本节点缓存以兼容存量配置。

验证工作分三个层面推进。功能层面在每个节点池抽样执行 `dig @169.254.20.10 kubernetes.default` 与外部域名查询，确认响应正确；链路层面抽查业务 Pod 的 resolv.conf，确认 nameserver 已替换。效果层面通过对比 coredns_dns_requests_total 指标与业务侧 DNS P99 曲线确认收益，本集群上线后 CoreDNS 上游 QPS 从峰值十四万降到三万以内，应用侧解析 P99 从九十毫秒降到五毫秒左右，偶发五秒超时告警归零。回退方案保留在运维 Wiki：把 DaemonSet 缩容到零并恢复 CoreDNS Corefile，业务 Pod 重建后即恢复原有链路，因此上线过程本身风险可控，这也是该方案被列入年度必做项的原因。

## 5. 调度与资源管理

### 5.1 requests 与 limits 设定

requests 与 limits 的语义差异是资源管理一切讨论的起点：requests 是调度承诺，kubelet 依据它做节点装箱决策；limits 是运行时硬约束，对应 cgroup 的配额上限。按两者配比关系，Pod 被划分为 Guaranteed（两者相等且所有容器都设置了）、Burstable（介于中间）与 BestEffort（都没设）三个 QoS 等级，节点资源紧张时的驱逐顺序从 BestEffort 开始，核心服务必须落在 Guaranteed 等级。CPU limit 的一个高频坑是 CFS 节流：容器配额一核而应用在高峰期需要两核时，内核会按周期节流进程，表现为接口延迟按一百毫秒粒度抖动、监控 CPU 使用率却很健康，确认方法是查看容器指标中 container_cpu_cfs_throttled_seconds_total 的增量，若节流时间占比超过百分之十应重新评估 limit。

### 5.2 节点资源压力判读

requests 虚高的管理问题往往在半年维度的成本复盘里才暴露：某个服务把 requests.memory 设为四 GB 而实际只用八百 MB，集群整体分配率触顶后新 Pod 全部 Pending，扩容节点又推高成本。规范做法是先用 `kubectl describe node` 逐节点核对 Allocated resources 段的分配率与真实用量之间的差值，再用 VPA 的 recommendation 模式跑两周采集真实水位曲线，随后分批下调 requests 并观察 HPA 行为。之所以要分批，是因为第 5.3 节的 HPA 利用率以 requests 为分母，requests 下调会瞬间推高利用率读数并触发扩容，二者必须联动评估、同步调整，这是资源治理中联锁关系最典型的一例。

### 5.3 HPA 弹性伸缩治理

公司 HPA 基线以 CPU 利用率指标为主、队列积压等自定义指标为辅，核心公式是期望副本数 = 当前副本数 × 当前利用率 / 目标利用率，其中利用率的分母是 Pod 的 requests 而非节点总量，因此 requests 设得离谱会直接扭曲扩缩容行为——requests 虚高则利用率读数永远偏低、HPA 从不扩容，最终以级联式过载收场；requests 虚低则会在流量低谷期疯狂扩容。防抖动的配置经验是把缩容稳定窗口 behavior.scaleDown.stabilizationWindowSeconds 设为三百秒（五分钟内指标恢复就不缩）、缩容步长策略限制为每分钟最多一个副本，并在扩容侧设置四十秒的稳定窗口防止毛刺触发误扩。管理上必须为每个 HPA 设置 minReplicas（结合 PDB 预算）与 maxReplicas（结合集群水位与预算），后者未设上限的服务曾在一夜之间扩到两百副本，把集群余量全部吃光。

## 6. 集群版本升级管理

### 6.1 升级前置评估

集群升级的风险敞口集中在 API 废弃与组件兼容两个维度，因此前置评估必须提前两周启动。版本偏差策略要求 kubelet 版本不得高于 API Server，最多可低三个小版本，升级顺序因此固定为控制面先行、节点分批跟随，且不支持跨小版本升级，从 1.26 到 1.28 必须逐版本推进。评估阶段的三项必做动作是：其一，用集群巡检工具扫描全量清单中已废弃 API（1.26 之后重点检查 batch/v1beta1 与 policy/v1beta1 残留），提前两周通知业务方改造；其二，确认存储 CSI、CNI、CoreDNS、监控 Agent 等生态组件在目标版本的兼容矩阵；其三，对 etcd 执行 `etcdctl snapshot save` 并把快照异地归档，这是升级失败后唯一的完整回退手段。评估结论以变更单形式提交 SRE 评审组审批，未获审批的升级一律不得排期。

### 6.2 升级执行与监控

控制面升级在业务低峰窗口执行，标准命令序列为 `kubeadm upgrade plan` 确认可达版本与前置条件，随后 `kubeadm upgrade apply v1.27.x` 依次更新三个控制面节点，期间 API Server 短暂重启造成的抖动会表现为 kubectl 偶发连接拒绝，属于预期现象，不应触发误操作。升级过程中值班组需要盯守三项核心指标：etcd 的 leader 选举与 fsync 延迟、API Server 的请求错误率、以及 CoreDNS 的解析成功率，任何一项持续恶化都应暂停推进。工作节点的批次升级由节点维护手册的标准流程驱动，本文档只约束批次纪律：每批次不超过总节点数的十分之一，批次间间隔三十分钟并确认上一批业务指标平稳，HPA 与 PDB 在升级期临时冻结自动缩容，防止缩容与腾空叠加造成容量不足。

### 6.3 升级后验证与回退

升级完成后必须完成一轮全链路冒烟验证才算闭环：功能层面抽查三个核心服务的端到端请求，确认写请求幂等不重复；调度层面观察十分钟内新 Pod 调度成功率与 Pending 数量；稳定性层面确认 metrics-server、CoreDNS、Ingress 控制器、日志采集器全部健康。回退策略在 6.1 节已埋下伏笔：控制面升级本质上不可逆——etcd 数据结构升级后无法直接回滚二进制，唯一手段是用升级前快照执行 `etcdctl snapshot restore` 重建 etcd，该操作会导致升级窗口内写入的数据丢失，因此回退决策必须在升级后一小时内做出、且需变更经理与业务负责人双签确认。每次升级无论成败，一周内必须产出复盘文档并更新本手册的版本兼容矩阵。

## 7. 附录

### 7.1 升级路径与外部依赖

P1 级故障（核心交易不可用、集群控制面不可达）直接电话通知值班经理并在运维群全员呼叫，P2 级（单服务异常、批量 Pod 异常）由值班工程师先行处置三十分钟无结论再升级。公司内部依赖三份配套文档：OPS-K8S-002 节点维护手册（节点故障与腾空流程）、OPS-MON-005 监控告警基线（各指标阈值与告警分级）、OPS-RELEASE-008 发布平台操作规范（发布与回滚单据流程），本文档与它们交叉引用处均已注明编号，冲突时以最新版本的专门手册为准。

### 7.2 常用排障命令速查

| 场景 | 命令 |
|------|------|
| 查看事件 | `kubectl get events -n <ns> --sort-by=.lastTimestamp` |
| 读取崩溃前日志 | `kubectl logs <pod> --previous --tail=100` |
| 注入临时调试容器 | `kubectl debug -it <pod> --image=busybox --target=<c> -- sh` |
| 端点健康检查 | `kubectl get endpoints <svc> -n <ns>` |
| 更新停滞检查 | `kubectl rollout status deploy/<name>` |
