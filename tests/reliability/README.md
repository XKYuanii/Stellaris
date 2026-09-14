# Reliability integration tests

These scripts run the reliability checks that require real infrastructure. Both detect the
Docker Engine's minimum API version before Maven starts; this is required on Docker Engine 29+
when the Spring Boot dependency set still manages Testcontainers 1.19.x.

- `Invoke-RedisLuaIntegration.ps1`: starts isolated Redis 7 containers and verifies the
  reservation safety guards, abandoned PEL reclaim, and atomic XACK+XDEL.
- `Invoke-TradeDatabaseIntegration.ps1`: starts an isolated MySQL 8 container and verifies the
  order idempotency key plus the payment/cancellation compare-and-set race.
- `verify-order-redis-transition.ps1`: injects and cleans synthetic records in the local interview
  MySQL/Redis environment to exercise the running Order scheduler end to end.

Run from the repository root:

```powershell
./tests/reliability/Invoke-RedisLuaIntegration.ps1 # Lua + PEL claim + atomic XACK/XDEL
./tests/reliability/Invoke-TradeDatabaseIntegration.ps1
```
