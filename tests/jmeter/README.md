# Stellaris JMeter 测试

`v5-create.jmx` 测量 V5 创建接口的准入时延。HTTP 200 中的业务失败也会记为失败样本，因此错误率同时覆盖 HTTP 和业务错误。

## 冒烟运行

从仓库根目录执行：

```powershell
# 运行前将 JAVA_HOME 指向 JDK 17，将 JMETER_HOME 指向 JMeter 安装目录。
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$jmeter = Join-Path $env:JMETER_HOME 'bin\jmeter.bat'
$dataFile = (Resolve-Path 'tests\jmeter\data\v5-benchmark-users.csv').Path
& $jmeter `
  -n -t 'tests\jmeter\v5-create.jmx' `
  '-Jhost=127.0.0.1' '-Jport=6085' `
  '-Jpath=/stellaris/program/program/order/create/v5' `
  '-JprogramId=900000' '-JticketCategoryId=900001' `
  "-JdataFile=$dataFile" `
  '-Jthreads=1' '-Jloops=1' '-Jramp=1' `
  -l "output\jmeter\$runId-result.jtl" -e -o "output\jmeter\$runId-html"
```

JMeter 使用 JDK 17。正式运行前先执行 `tests/benchmark/Prepare-StellarisV5Benchmark.ps1`，再执行 `tests/benchmark/Invoke-StellarisV5FunctionalCheck.ps1`；运行后用 `tests/benchmark/Cleanup-StellarisV5Benchmark.ps1` 清理测试订单并恢复夹具。

创建接口只测到“Redis Lua 完成锁座并写入 Stream”的受理边界。端到端指标还要轮询 `/stellaris/order/order/materialization`，直到 `t_order_request` 返回 `CREATED` 或 `REJECTED`，并校验 Stream/PEL 归零、交易库无重复订单和无超卖。

每轮复位范围是隔离节目在 `stellaris_trade` 中的订单、购票计数和座位交易态，以及对应 Redis 座位键、Stream 与 PEL。当前链路没有 Kafka，旧结果中的 Kafka offset 不能作为当前版本的验收项。
