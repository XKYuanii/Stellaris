# Stellaris JMeter tests

`v5-create.jmx` measures the admission response of the V5 create endpoint. It marks HTTP-success/business-failure responses as failed samples, so the reported error rate is not limited to HTTP status codes.

## Current-data smoke test

Run from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Users\X\.jdks\ms-17.0.17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
& 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat' `
  -n `
  -t 'tests\jmeter\v5-create.jmx' `
  '-Jhost=127.0.0.1' `
  '-Jport=6086' `
  '-Jpath=/program/order/create/v5' `
  '-JprogramId=32' `
  '-JticketCategoryId=34' `
  '-Jthreads=1' `
  '-Jloops=1' `
  '-Jramp=1' `
  -l "output\jmeter\$runId-result.jtl" `
  -e `
  -o "output\jmeter\$runId-html"
```

JMeter must also run on JDK 17. On this workstation the Oracle `javapath` entry precedes `JAVA_HOME` and otherwise starts Java 25, which makes JMeter/Groovy fail with `Unsupported class file major version 69`.

For the gateway path, change port/path and provide a current token:

```text
-Jport=6085
-Jpath=/stellaris/program/program/order/create/v5
-Jtoken=<token>
```

## Important data boundary

The checked-in CSV only contains the three users whose ticket-user ownership was verified through the running user-service API. Program 32 limits each account to six tickets. Therefore the current fixture supports at most 18 fresh successful orders before account-limit failures, and it must only be used to debug the JMeter plan.

Do not quote its throughput as V5 capacity. A real baseline needs a dedicated benchmark program, enough seats and enough benchmark users, plus a scoped reset that restores MySQL, V5 Redis keys, Stream/PEL state and Kafka test offsets between runs.

The create sampler measures admission latency. V5 end-to-end throughput must additionally measure the time from create acceptance until `/order/get/cache` returns the order number or the order becomes queryable.

## Dedicated V5 fixture

Run `tests/benchmark/Prepare-StellarisV5Benchmark.ps1` to create program `900000`, category `900001`, 10,000 seats and `v5-benchmark-users.csv`. Select it with:

```text
-JprogramId=900000
-JticketCategoryId=900001
-JdataFile=G:\study\Computer\java\stellaris-platform\tests\jmeter\data\v5-benchmark-users.csv
```

Before a formal capacity run, use `tests/benchmark/Invoke-StellarisV5FunctionalCheck.ps1`. That script runs only 1/3/10-thread correctness checks and cleans up afterwards.
