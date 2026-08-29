# 配置变更台账

每项变更必须记录修改前值、修改后值、生效范围、原因和回滚方式。临时命令行参数随进程退出自动失效；项目文件修改使用精确反向补丁恢复。

## C001：V4/V5 JMeter 预建连和同步栅栏

- 时间：2026-08-28
- 文件：`tests/benchmark/v4-v5-comparison.jmx`
- 类型：测试工具配置，不修改业务服务配置
- 修改前：线程启动时间固定为 `1` 秒；同步定时器位于线程组作用域；栅栏等待超时 `30000ms`；没有预建连步骤。
- 修改后：线程启动时间为 `${__P(threadRampSeconds,1)}`；增加由 `${__P(preconnect,false)}` 控制的 `/actuator/health` Keep-Alive 预建连；预建连必须返回 HTTP 200；同步定时器仅作用于正式下单采样器；栅栏等待超时为 `120000ms`。
- 原因：确保 V4/V5 使用相同连接模式，并支持高并发线程在正式请求前完成连接建立。
- 影响：仅影响使用该 JMX 的 V4/V5 对照测试；默认 `preconnect=false` 时不会执行预建连。
- 回滚：删除 Optional Keep-Alive Preconnect 控制器；把 `ThreadGroup.ramp_time` 恢复为 `1`；将同步定时器移回线程组并把超时恢复为 `30000`。
- 当前状态：保留，属于正式对照测试设计。

## 尚未发生的修改

- 未修改任何服务 `application.yml`。
- 未修改 IntelliJ IDEA 日常启动配置。
- 未修改 Docker Compose 配置。
- 未修改 MySQL、Redis 或 Kafka 的运行参数。
- 未设置持久化的系统环境变量。

## E001：首次独立服务启动尝试

- 时间：2026-08-28 22:50（Asia/Shanghai）
- 结果：启动脚本健康检查参数使用了与 PowerShell 只读 `$PID` 变量冲突的名称，脚本在调用健康检查前终止。
- 数据影响：没有执行压测，没有创建测试订单；异常清理已停止本次刚创建的 Order JVM。
- 遗留配置：无，JVM参数均为进程级且进程已经退出。
- 修正：健康检查参数从 `Pid` 重命名为 `ProcessId`，不改变任何测试口径或业务配置。

## E002：第二次独立服务启动尝试

- 时间：2026-08-28 22:52（Asia/Shanghai）
- 结果：Program/Order 均已通过健康检查，但 PowerShell 将泛型 List 直接写入状态对象时出现 `Argument types do not match`。
- 数据影响：没有执行压测，没有创建测试订单；异常清理已停止本次创建的 Program/Order JVM。
- 遗留配置：无。
- 修正：将服务状态集合显式枚举为普通对象数组后再序列化，不改变服务启动参数。

## C002：独立低内存正式压测运行时

- 时间：2026-08-28 22:54（Asia/Shanghai）
- 类型：进程级临时配置；未修改任何 `application.yml`、IDEA配置或Docker配置。
- 运行服务：Order Service（PID 19008）、Program Service（PID 30860）。
- 公共JVM参数：`-Xms256m -Xmx768m -XX:MaxMetaspaceSize=384m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError`。
- 日志：Root和`com.damai`临时设为WARN；启用GC与Safepoint滚动日志。
- 移除的开发环境干扰：未使用 `-XX:TieredStopAtLevel=1`，未加载IDEA Java Agent。
- Program JAR SHA-256：`7D88B72A6AF2C221EF693B6BEE1A8B05DC2B6ED98555353D976CC5AABC29CA52`。
- Order JAR SHA-256：`4EBC0AE75DEACEEFA5CE509A93F5299328A8D8F0EEE5C84EE3CBBFC980CEEA35`。
- 生效范围：仅上述两个JVM的生命周期。
- 回滚命令：`& '.\tests\benchmark\Stop-V4V5BenchmarkServices.ps1'`。
- 当前状态：已于2026-08-28 22:57通过停止脚本完整回滚；两个PID均经JAR命令行校验后停止。

## C003：测试执行器指标增强

- 时间：2026-08-28 22:48（Asia/Shanghai）
- 新文件：`tests/benchmark/run-v4-v5-stage.ps1`。
- 新增指标：成功吞吐、成功样本P50/P95/P99、请求成功率、3秒SLO达标率、技术/业务失败分类、从突发开始到全部订单可见时间、Program/Order进程CPU与内存快照。
- 数据门禁：请求ID和成功订单号唯一；所有成功订单可见；每轮恢复必须为`PASS`。
- 过载模式：允许保留失败请求作为容量结果，但不放宽数据恢复和唯一性校验。
- 最大线程数：5000。
- 回滚：删除该新增脚本即可；未改变业务运行逻辑。

## E003：首次夹具预热依赖检查

- 时间：2026-08-28 22:57（Asia/Shanghai）
- 结果：隔离节目MySQL数据和V5快照创建成功，但Program预热通过Feign调用`xingyan-base-data-service/area/getById`时因BaseData未启动而返回系统错误；V4缓存未完成，`Ready`校验失败。
- 订单影响：没有执行下单请求，没有创建测试订单。
- 状态：MySQL隔离节目保持10000个可售座位；V5快照存在；V4缓存未创建。该中间状态不会进入测试统计。
- 处理：BaseData确认为准备/恢复阶段必要依赖，加入独立运行时并重新执行全量准备；只有`Ready PASS`后才开始冒烟。

## C004：补齐BaseData后的正式压测运行时

- 时间：2026-08-28 22:59～23:00（Asia/Shanghai）。
- 类型：进程级临时配置；未修改任何`application.yml`、IDEA配置、Docker配置或系统环境变量。
- BaseData：PID `18760`，端口`6083`，`-Xms256m -Xmx512m -XX:MaxMetaspaceSize=384m`，JAR SHA-256 `E4E531A6479EA7453DECF8B9A94FA9642337F59B4FDA7F033F7445D52FA33B0C`。
- Order：PID `13140`，端口`8081`，`-Xms256m -Xmx768m -XX:MaxMetaspaceSize=384m`，JAR SHA-256 `4EBC0AE75DEACEEFA5CE509A93F5299328A8D8F0EEE5C84EE3CBBFC980CEEA35`。
- Program：PID `21064`，端口`6086`，`-Xms256m -Xmx768m -XX:MaxMetaspaceSize=384m`，JAR SHA-256 `7D88B72A6AF2C221EF693B6BEE1A8B05DC2B6ED98555353D976CC5AABC29CA52`。
- 公共参数：G1 GC、OOM堆转储、GC/Safepoint滚动日志、Root和`com.damai`日志级别WARN、关闭Banner；未使用`-XX:TieredStopAtLevel=1`和IDEA Java Agent。
- 依赖边界：BaseData只服务于测试夹具预热/恢复所需的Feign查询，不属于V4/V5下单同步热路径统计范围。
- 启动后健康状态：三个服务`/actuator/health`均为`UP`；系统可用内存3.61GB。
- 启动后快照：BaseData/Order/Program驻留内存分别约585.0/687.2/709.4MB，线程数分别143/244/285。
- 生效范围：仅上述三个JVM的生命周期。
- 精确证据：`tests/benchmark/results/runtime-v4-v5/service-state.json`、`configuration-manifest.json`及每个服务的`jvm-command-line`、`jvm-flags`、`heap-after-start`文件。
- 回滚命令：`& '.\tests\benchmark\Stop-V4V5BenchmarkServices.ps1'`；脚本先验证PID命令行仍指向记录的JAR，再停止进程。
- 当前状态：已于2026-08-29 07:39通过停止脚本回滚；三个PID均经JAR命令行校验后停止。

## E004：V4 500并发探索轮被数据门禁淘汰

- 时间：2026-08-28 23:18～23:25（Asia/Shanghai）。
- HTTP结果：500个正式下单请求中479个返回有效订单号，21个HTTP 200响应的业务字段为空；请求成功率95.8%。
- 异步门禁：等待300秒后，479个HTTP成功订单中只有473个在MySQL可见，缺少6个。
- 恢复结果：随后仅对隔离节目执行机械恢复，10000座位、库存、V4/V5 Redis结构、Stream、PEL、Dead Stream和Kafka Lag终态均为`PASS`。
- 判定：本轮`INVALID`，TPS、时延和失败率均不得进入简历正式统计；停止继续执行V4 1000并发探索。
- 原始证据：`tests/benchmark/results/v4-v5-campaign/20260828-formal/exploration/v4-500/`。
- 工具修正：执行器摘要升级到schema v3；即使订单可见性门禁失败，也会记录可见订单数、等待时间、错误原因并将`validForComparison=false`，随后再执行恢复。
- 业务代码修改：无。

## C005：高并发JMeter客户端内存保护

- 时间：2026-08-28 23:43（Asia/Shanghai）。
- 生效轮次：V5 1500并发探索轮。
- 客户端参数：`-Xms384m -Xmx1792m -Xss512k -XX:MaxMetaspaceSize=256m`。
- 修改前：1000并发探索使用`-Xms384m -Xmx1536m -XX:MaxMetaspaceSize=256m`，线程栈使用JDK默认值。
- 原因：本机物理内存15.74GB，高并发JMeter一线程一栈；显式限制客户端线程栈，避免负载机内存挤压被误判为服务端问题。
- 边界：这是JMeter客户端资源参数，不是服务端JVM调优收益；不能拿它宣称服务端吞吐提升。
- 持久化影响：无，仅在该JMeter进程生命周期内生效，进程退出即回滚。
- 结果：1500个正式请求中1299成功、201个`Connection refused`；所有成功订单均可见，恢复`PASS`。

## C006：消除HTML报告生成对异步收敛时间的观测污染

- 时间：2026-08-29 02:45（Asia/Shanghai）。
- 文件：`tests/benchmark/run-v4-v5-stage.ps1`。
- 修改前：JMeter主测试命令使用`-e -o`，在进程返回前同步生成HTML报告；脚本随后才检查MySQL订单可见性。因此schema v2/v3中的`burstToAllOrdersVisibleMs`包含约12～15秒报告生成耗时，只能视为上界。
- 修改后：主测试只写JTL；HTTP结束后立即解析正式样本并等待全部成功订单可见；完成门禁后再使用`jmeter -g ... -o ...`离线生成HTML报告。摘要升级为schema v4，并增加`htmlReportGeneratedAfterVisibility=true`。
- 影响：不改变HTTP请求、服务端热路径、成功TPS或成功样本时延；只修正异步收敛时间的观测顺序。
- 历史边界：此前7轮100并发正式A/B的TPS、成功P50/P95/P99、成功率和SLO达标率有效；其`burstToAllOrdersVisibleMs`不得当作精确异步落库时间。
- 回滚：把主测试命令恢复为`-l $jtl -j $log -e -o $report`，重新读取`statistics.json`，并删除离线`jmeter -g`步骤；不建议回滚。

## C007：消除Windows端口枚举对收敛时间的观测污染

- 时间：2026-08-29 02:49（Asia/Shanghai）。
- 发现方式：schema v4的10并发冒烟虽已后置HTML报告，`burstToAllOrdersVisibleMs`仍约12秒；对照时间戳定位到HTTP结束后的两次`Get-NetTCPConnection`。
- 修改前：HTTP后再次按6086/8081端口枚举监听进程并采样，Windows上两次调用合计约12秒。
- 修改后：测试前仍按端口校验并记录PID；HTTP后直接按已验证PID调用`Get-Process`采样，不再枚举TCP表。
- 影响：不改变请求、服务或JMeter参数；只修正观测顺序和探针开销。
- 无效观测：`smoke/schema-v4-v5-10`的同步指标有效，`burstToAllOrdersVisibleMs`因探针污染不可使用。
- 回滚：把两个后置快照调用恢复为`Get-PortProcessSnapshot`；不建议回滚。

## C008：异步可见性指标改为保守上界命名

- 时间：2026-08-29 02:51（Asia/Shanghai）。
- 验证：修复报告生成与端口枚举污染后，V5 10并发的首次全量可见观测为突发后2152ms，其中HTTP窗口28ms、HTTP结束后的MySQL轮询318ms；剩余为JMeter退出、JTL解析和首次发起查询的观测延迟。
- 修改：schema v4不再输出容易被理解为精确时延的`burstToAllOrdersVisibleMs`，改为`allOrdersVisibleObservedByMsFromBurstStart`，并标注`orderVisibilityMeasurement=post-http polling; conservative observed-by upper bound`。
- 面试口径：只能说“全部成功订单最迟在突发后X毫秒被观测到”，不能说每个订单的端到端落库P99是X毫秒。
- 业务代码与测试负载：均未修改。

## C009：容量边界重复轮跳过冗余HTML报告

- 时间：2026-08-29 02:53（Asia/Shanghai）。
- 文件：`tests/benchmark/run-v4-v5-stage.ps1`。
- 新增参数：`-SkipHtmlReport`；仅跳过JTL到HTML的离线转换，仍保存JTL、JMeter日志、失败分类和summary.json。
- 使用范围：3秒SLO边界的重复确认轮；探索轮和100并发正式A/B已经保留HTML报告。
- 原因：HTML转换不提供新的统计样本，重复生成会增加每轮工具耗时和内存压力。
- 性能口径影响：无；JTL是原始数据源，所有摘要指标从JTL计算。
- 回滚：不传`-SkipHtmlReport`即可恢复生成；无持久化配置。

## E005：批次间主机挂起/恢复

- 时间：2026-08-29 03:00～07:22（Asia/Shanghai，按测试日志墙钟判断）。
- 现象：V4 SLO边界批次的独立轮次之间出现约4小时墙钟空档，符合主机睡眠/挂起后恢复特征；没有请求正在执行的证据。
- 数据处理：每一轮开始前均重新执行`Ready PASS`和5000用户缓存校验；性能窗口只使用JTL中正式请求首个开始到最后响应的时间，不包含跨轮墙钟空档。
- 结论：已完成的独立轮仍有效，但报告披露该运行环境事件；不把批次总耗时作为任何性能指标。

## C010：V5容量确认前等配置重启

- 时间：2026-08-29 07:39～07:40（Asia/Shanghai）。
- 原因：长时间运行及多轮压测后主机可用内存降至约1.25GB；为避免负载机内存压力污染1500线程测试，先通过停止脚本回滚，再按同一清单重启。
- 配置变化：无。JVM参数、日志级别、JDK路径及三个JAR SHA-256均与C004一致。
- 新PID：BaseData `10504`、Order `35144`、Program `21352`。
- JAR哈希：BaseData `E4E531A6479EA7453DECF8B9A94FA9642337F59B4FDA7F033F7445D52FA33B0C`；Order `4EBC0AE75DEACEEFA5CE509A93F5299328A8D8F0EEE5C84EE3CBBFC980CEEA35`；Program `7D88B72A6AF2C221EF693B6BEE1A8B05DC2B6ED98555353D976CC5AABC29CA52`。
- 公平性措施：V5容量确认前重新执行全量准备与缓存预热，并增加不计分的20并发热身。
- 回滚命令：`& '.\tests\benchmark\Stop-V4V5BenchmarkServices.ps1'`。
- 当前状态：该运行实例已在C014重启时停止；C014实例也已在C016完成最终回滚。

## C011：V5高并发负载机内存隔离

- 时间：2026-08-29 07:42～07:47（Asia/Shanghai）。
- 环境操作：临时停止与购票链路无关的`xingyan-interview-elasticsearch-1`容器；MySQL、Redis、Kafka、Nacos保持运行且健康，三个业务服务健康检查仍为`UP`。
- 原状态：Elasticsearch容器运行，内存约1.13GiB。
- 当前状态：该临时操作已结束；用户已于2026-08-29恢复Elasticsearch，后续最终测试保持其healthy运行。
- WSL操作：尝试终止无关Ubuntu发行版以释放内存，但Docker集成随即使其恢复为Running；不把该操作视为有效优化。
- V5 1000/1500边界统一JMeter参数：`-Xms384m -Xmx1536m -Xss256k -XX:MaxMetaspaceSize=256m`。
- 与C005差异：线程栈由512k进一步限制为256k，且两个比较档位完全一致；目的是避免JMeter一线程一栈耗尽16GB主机内存。
- 边界：以上是负载机隔离，不是Program Service的JVM调优成果；报告必须单独披露。

## E006：V5 1500确认轮未进入压测

- 时间：2026-08-29 11:15前后（主机长时间挂起恢复后）。
- 结果：在JMeter启动前，5000用户Redis缓存的分批MGET校验返回命令失败；正式HTTP请求数为0，未创建结果目录或测试订单。
- 处理：不计入任何统计；用户随后恢复了Elasticsearch，当前所有中间件健康，夹具再次验证为`Ready PASS`。

## C012：高并发预建连门禁与最终客户端参数

- 时间：2026-08-29（恢复继续后）。
- 执行器：摘要升级为schema v5，新增`preconnectRequests/preconnectSuccess/preconnectFailed/connectionPreparationStatus`。
- 门禁：启用`-Preconnect`时，必须恰好记录线程数相同的GET预建连且全部HTTP 200；否则该轮恢复后标为无效并抛错。
- V5 1000/1500最终确认统一参数：`-Xms256m -Xmx1g -Xss256k -XX:MaxMetaspaceSize=256m`，`PreconnectRampSeconds=30`。
- 先前V5 1000 run-1：使用1.5GB堆、5秒Ramp，仅作为客户端参数探测；移出最终确认目录，不进入5轮边界统计。
- 原因：确保正式突发确实复用已建立连接，同时适配当前约1GB可用物理内存，降低负载机OOM风险。
- 服务端配置：无变化。

## C013：预建连摘要标签修正

- 时间：2026-08-29。
- 问题：JMX实际样本标签为`Preconnect Program Service`，schema v5执行器最初按旧名称`GET Keep-Alive Preconnect`筛选，导致V5 1500 run-1虽在原始JTL中记录了1500/1500次成功预建连，摘要仍误报0次并标为无效。
- 修正：执行器改为按JMX中的实际标签筛选；未改变请求、并发释放、服务端或JMeter资源参数。
- run-1证据：`result.jtl`按`label/success/responseCode`分组为1500条`Preconnect Program Service,true,200`和1500条`POST Auto Seat Comparison,true,200`。
- 数据处理：仅依据不可变的原始JTL纠正run-1摘要中的4个派生字段：`preconnectRequests=1500`、`preconnectSuccess=1500`、`connectionPreparationStatus=PASS`、`validForComparison=true`；TPS、时延、SLO和订单可见性数值均未改动。
- 回滚：将执行器筛选标签恢复为旧名称会再次造成误判，不建议回滚；若需要完全重算，可从该轮`result.jtl`重新生成摘要。

## E007：Elasticsearch由用户恢复

- 时间：2026-08-29。
- 状态：用户发现Elasticsearch已关闭并手动重新启动；当前`xingyan-interview-elasticsearch-1`为healthy。
- 后续口径：继续测试时保持Elasticsearch运行，不再把临时停止ES作为最终V5边界测试环境，因此C011中的“最终交付前恢复”已经完成。

## C014：最终最佳实测筛选前等配置重启

- 时间：2026-08-29 12:01～12:02（Asia/Shanghai）。
- 原因：排除连续高并发轮次遗留的连接池、线程池和JIT运行状态；在最后的1000/1500单轮检查前建立干净运行时。
- 停止校验：停止脚本先校验PID命令行仍匹配记录的BaseData、Order、Program JAR，再停止PID `10504/35144/21352`。
- 新PID：BaseData `19204`、Order `4572`、Program `1840`。
- JVM配置：与C004/C010完全一致；BaseData为`Xms256m/Xmx512m`，Program/Order为`Xms256m/Xmx768m`，Metaspace上限384m、G1、WARN日志、GC/Safepoint日志。
- JAR哈希：三个JAR与C004/C010完全一致；未重新编译或替换业务代码。
- 中间件：MySQL、Redis、Kafka、Elasticsearch、Nacos均保持healthy。
- 夹具：重建节目`900100`/票档`900101`的10000座位并预热5000用户缓存，开始前`Ready PASS`。
- 生效范围：仅三个JVM进程生命周期；最终交付前使用停止脚本回滚。

## C015：最终对外统计选择

- 100并发优化指标：固定使用7轮交替A/B中位数，不挑最好一次。
- SLO容量指标：按用户要求使用通过订单可见性、唯一性及机械恢复门禁的最佳有效实测，并明确标注“最佳实测”。
- 对外V5容量证据：1000个正式POST全部成功，成功TPS 931.10、成功P99 1028ms、3秒SLO达标率100%、订单可见与恢复PASS。
- 边界：不得表述为“1000 QPS”或“多轮稳定容量”；探索/诊断轮原始文件保留在`tests/benchmark/results`，不删除、不覆盖，也不混入简历主指标。

## C016：最终运行时回滚

- 时间：2026-08-29（最终数据与文档校验后）。
- 回滚前终态：节目`900100`的MySQL 10000座位、票档余量、V4/V5 Redis结构、Stream、PEL、Dead Stream、延迟任务和Kafka Lag全部`PASS`。
- 停止对象：BaseData PID `19204`、Order PID `4572`、Program PID `1840`；停止脚本逐个校验进程命令行中的JAR名称后执行。
- 回滚结果：三个临时JVM均已停止，端口6083/6086/8081不再监听；进程级Xms/Xmx、Metaspace、日志级别和GC日志参数均不再生效。
- 保持不变：未修改`application.yml`、IDEA运行配置、Docker Compose和系统环境变量。
- Docker终态：MySQL、Redis、Kafka、Elasticsearch、Nacos均保持healthy；未停止用户恢复的Elasticsearch。

## E008：主机重启后的Docker Desktop失效Socket

- 时间：2026-08-29 12:53～13:16（Asia/Shanghai）。
- 现象：主机重启后Docker Desktop无法启动，CLI无法连接Linux Engine；业务JVM和JMeter均未启动，正式压测请求数为0。
- 第一处根因：`C:\Users\X\AppData\Local\Docker\run\dockerInference`为2026-08-28遗留的不可访问AF_UNIX重解析点，Docker后台报错后退出。
- 可恢复处理：确认Docker进程退出且路径位于Docker运行时目录后，将整个`run`目录重命名为`C:\Users\X\AppData\Local\Docker\run-stale-20260829-1259`，未删除任何文件、镜像、容器或卷。
- 第二处根因：随后Docker在`C:\Users\X\AppData\Local\docker-secrets-engine\engine.sock`遇到同类失效Socket；该目录即使在`wsl --shutdown`和Docker进程全部退出后仍需管理员权限才能移动。
- 诊断迭代：Docker再次启动时生成的新`run`目录也留下Inference Socket，已完整保留为`C:\Users\X\AppData\Local\Docker\run-stale-20260829-1311`。
- Docker设置备份：`C:\Users\X\AppData\Roaming\Docker\settings-store.pre-benchmark-20260829-1308.json`，备份时与原文件SHA-256一致。
- 临时设置试验：把`EnableDockerAI/EnableInference/EnableDockerMCPToolkit/InferenceCanUseGPUVariant`设为false，验证Secrets Engine仍为强制启动组件后已恢复原配置；当前`settings-store.json`中的显式字段与试验前一致。
- 管理员操作：尝试通过UAC仅重命名`docker-secrets-engine`目录，但UAC被用户取消，没有发生修改。
- 后续实况：用户于本批次前手动启动Docker Desktop成功；Linux Engine版本`29.1.3`，MySQL、Redis、Kafka、Elasticsearch、Nacos五个压测容器均为healthy。原`docker-secrets-engine`目录仍存在，说明管理员重命名并未发生，也不是此次成功启动的必要条件。
- 配置终态：Docker `settings-store.json`已恢复到试验前内容且与备份SHA-256一致；没有持久化Docker配置变更。两个可恢复的旧运行时目录`run-stale-20260829-1259`和`run-stale-20260829-1311`仍保留，未删除。
- 当前状态：阻塞已解除；没有执行Factory Reset，没有删除镜像、容器、卷或Secrets Engine目录。

## C017：重启后四档复核运行时

- 时间：2026-08-29 13:36～13:39（Asia/Shanghai）。
- 中间件：`xingyan-interview-mysql-1`、`redis-1`、`kafka-1`、`elasticsearch-1`、`nacos-1`均保持healthy；没有修改Compose或容器参数。
- BaseData：PID `3280`，端口`6083`，`-Xms256m -Xmx512m -XX:MaxMetaspaceSize=384m`，JAR SHA-256 `E4E531A6479EA7453DECF8B9A94FA9642337F59B4FDA7F033F7445D52FA33B0C`。
- Order：PID `5204`，端口`8081`，`-Xms256m -Xmx768m -XX:MaxMetaspaceSize=384m`，JAR SHA-256 `4EBC0AE75DEACEEFA5CE509A93F5299328A8D8F0EEE5C84EE3CBBFC980CEEA35`。
- Program：PID `8120`，端口`6086`，`-Xms256m -Xmx768m -XX:MaxMetaspaceSize=384m`，JAR SHA-256 `7D88B72A6AF2C221EF693B6BEE1A8B05DC2B6ED98555353D976CC5AABC29CA52`。
- 公共参数：G1、Metaspace上限384m、OOM堆转储、GC/Safepoint滚动日志、Root与`com.damai`为WARN；不含IDEA Agent和`TieredStopAtLevel=1`。
- 准备门禁：节目`900100`、票档`900101`、10000座位、5000用户缓存完成准备；MySQL、Redis、Stream、PEL、Dead Stream和Kafka Lag的`Ready`校验全部`PASS`。
- 生效范围：仅上述三个JVM进程；未修改`application.yml`、IDEA运行配置或系统环境变量。
- 精确回滚：`& '.\tests\benchmark\Stop-V4V5BenchmarkServices.ps1'`。脚本会先核对PID命令行中的JAR再停止，进程退出后所有JVM和日志参数自动失效。
- 当前状态：按用户“打开所有服务”的要求继续运行，没有在交付时停止。

## C018：重启后四档单次复核参数与结果

- 时间：2026-08-29 13:42～13:55（Asia/Shanghai）。
- 执行次数：严格只执行V4-100、V5-100、V5-1000、V5-1500各一次，没有追加复测。
- 共同参数：`-Preconnect -SloThresholdMs 3000 -OrderVisibilityTimeoutSeconds 300 -AllowRequestFailures -MechanicalRecovery -SkipHtmlReport`；HTTP响应超时由JMX保持90秒。
- V4-100与V5-100客户端JVM：`-Xms256m -Xmx1g -Xss256k -XX:MaxMetaspaceSize=256m`，预建连坡度5秒。
- V5-1000客户端JVM：`-Xms256m -Xmx1536m -Xss256k -XX:MaxMetaspaceSize=256m`，预建连坡度5秒。
- V5-1500客户端JVM：`-Xms256m -Xmx1536m -Xss256k -XX:MaxMetaspaceSize=256m`，预建连坡度30秒。
- V4-100：100/100成功，成功TPS 9.47，Avg 5887.78ms，P95 10124ms，P99 10478ms，3秒SLO达标率19%，回收`PASS`。
- V5-100：100/100成功，成功TPS 452.49，Avg 192.82ms，P95 216ms，P99 220ms，3秒SLO达标率100%，回收`PASS`。
- V5-1000：正式POST 1000/1000成功，成功TPS 533.05，P99 1851ms，SLO达标率100%，回收`PASS`；前置GET仅851/1000成功，因此严格预建连门禁`FAIL`且`validForComparison=false`。
- V5-1500：预建连1500/1500成功；正式POST 1466/1500成功，成功TPS 667.58，成功P99 2081ms，34个技术失败均为`Connection refused`，成功率/SLO达标率97.733%，回收`PASS`。
- 100并发单轮派生：成功TPS提升47.78倍，Avg降低96.73%，P99降低97.90%，3秒SLO达标率提升81个百分点。
- 数据终态：每轮成功订单全部可见；每轮机械恢复后10000座位、Redis、Stream、PEL、Dead Stream及Kafka Lag均`PASS`。
- 结果目录：`tests/benchmark/results/v4-v5-campaign/20260829-final-one-shot/`。
- 面试取值：正式简历仍使用7轮交替A/B中位数和此前最佳有效V5-1000证据；本批次只作单次复核，不覆盖主结论。
