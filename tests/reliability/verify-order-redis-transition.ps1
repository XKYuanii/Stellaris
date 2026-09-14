param(
    [string]$RedisContainer = "stellaris-local-redis-1",
    [string]$MysqlContainer = "stellaris-local-mysql-1"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Redis {
    param([string[]]$RedisArguments)
    $output = & docker exec $RedisContainer redis-cli --no-auth-warning -a redis123 @RedisArguments
    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli failed: $($RedisArguments -join ' ')"
    }
    return $output | Select-Object -Last 1
}

function Invoke-TradeSql {
    param([string]$Sql)
    $output = & docker exec $MysqlContainer mysql -uroot -pmysql123 -N -B -D stellaris_trade -e $Sql 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "trade SQL failed"
    }
    return $output
}

function Assert-Equal {
    param([string]$Name, [AllowEmptyString()][string]$Actual, [AllowEmptyString()][string]$Expected)
    if ($Actual -ne $Expected) {
        throw "$Name expected '$Expected', got '$Actual'"
    }
}

function Wait-Transition {
    param([long]$EventId, [string[]]$TerminalStates)
    $deadline = (Get-Date).AddSeconds(25)
    do {
        $row = Invoke-TradeSql "SELECT CONCAT(event_status,'|',retry_count,'|',COALESCE(last_error,'')) FROM d_reservation_transition_event WHERE id=$EventId"
        foreach ($state in $TerminalStates) {
            if ($row -like "$state|*") {
                return $row
            }
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "transition $EventId did not reach $($TerminalStates -join '/') within 25 seconds; last='$row'"
}

function Remove-SyntheticScenario {
    param(
        [long]$EventId,
        [long]$CommandId,
        [long]$OrderNumber,
        [string[]]$ProgramKeys,
        [string]$GlobalExpirationKey,
        [string]$ExpirationMember
    )
    [void](Invoke-TradeSql "DELETE FROM d_reservation_transition_event WHERE id=$EventId OR command_id=$CommandId OR order_number=$OrderNumber")
    [void](Invoke-Redis (@("DEL") + $ProgramKeys))
    [void](Invoke-Redis @("ZREM", $GlobalExpirationKey, $ExpirationMember))
}

# Normal reservation: IDs use the same string encoding as referenceReserveSeats.lua.
[long]$normalProgramId = 880009130300005
[long]$normalSeatId = 930000000000009849
[long]$normalUserId = 920000000000000000
[long]$normalOrderNumber = 880009130300008
[long]$normalEventId = 880009130300009
[long]$normalCommandId = 880009130300010
[long]$normalCategoryId = 900001
$normalIntent = "audit-live-release-20260913"
$normalShard = [int]($normalProgramId % 16)
$normalPrefix = "stellaris:{sale:$normalShard}:program:${normalProgramId}:seat:"
$normalMeta = "${normalPrefix}meta"
$normalOwner = "${normalPrefix}owner"
$normalReservation = "${normalPrefix}reservation"
$normalResult = "${normalPrefix}reservation:result"
$normalFinal = "${normalPrefix}reservation:final"
$normalAccount = "${normalPrefix}account-count"
$normalExpiration = "${normalPrefix}reservation:expiration"
$normalAvailable = "${normalPrefix}available:${normalCategoryId}"
$normalSold = "${normalPrefix}sold"
$normalGlobalExpiration = "stellaris:{sale:$normalShard}:reservation:expiration:index"
$normalMember = "$normalProgramId|$normalIntent"
$normalKeys = @($normalMeta, $normalOwner, $normalReservation, $normalResult, $normalFinal,
    $normalAccount, $normalExpiration, $normalAvailable, $normalSold)

# Corrupt legacy reservation: numeric snowflake IDs lose precision in Redis Lua cjson.
[long]$corruptProgramId = 880009130400005
[long]$corruptOrderNumber = 880009130400008
[long]$corruptEventId = 880009130400009
[long]$corruptCommandId = 880009130400010
$corruptIntent = "audit-corrupt-release-20260913"
$corruptShard = [int]($corruptProgramId % 16)
$corruptPrefix = "stellaris:{sale:$corruptShard}:program:${corruptProgramId}:seat:"
$corruptMeta = "${corruptPrefix}meta"
$corruptOwner = "${corruptPrefix}owner"
$corruptReservation = "${corruptPrefix}reservation"
$corruptResult = "${corruptPrefix}reservation:result"
$corruptFinal = "${corruptPrefix}reservation:final"
$corruptAccount = "${corruptPrefix}account-count"
$corruptExpiration = "${corruptPrefix}reservation:expiration"
$corruptAvailable = "${corruptPrefix}available:${normalCategoryId}"
$corruptSold = "${corruptPrefix}sold"
$corruptGlobalExpiration = "stellaris:{sale:$corruptShard}:reservation:expiration:index"
$corruptMember = "$corruptProgramId|$corruptIntent"
$corruptKeys = @($corruptMeta, $corruptOwner, $corruptReservation, $corruptResult, $corruptFinal,
    $corruptAccount, $corruptExpiration, $corruptAvailable, $corruptSold)

try {
    Remove-SyntheticScenario $normalEventId $normalCommandId $normalOrderNumber $normalKeys $normalGlobalExpiration $normalMember
    Remove-SyntheticScenario $corruptEventId $corruptCommandId $corruptOrderNumber $corruptKeys $corruptGlobalExpiration $corruptMember

    [void](Invoke-Redis @("HSET", $normalMeta, "$normalSeatId", '{"rowCode":1,"colCode":7}'))
    [void](Invoke-Redis @("HSET", $normalOwner, "$normalSeatId", $normalIntent))
    $normalReservationJson = "{`"userId`":`"$normalUserId`",`"ticketCount`":1,`"seats`":[{`"seatId`":`"$normalSeatId`",`"ticketCategoryId`":`"$normalCategoryId`"}],`"streamId`":`"0-1`"}"
    [void](Invoke-Redis @("HSET", $normalReservation, $normalIntent, $normalReservationJson))
    [void](Invoke-Redis @("HSET", $normalResult, $normalIntent, '[{"id":"930000000000009849"}]'))
    [void](Invoke-Redis @("HSET", $normalAccount, "$normalUserId", "1"))
    $normalScore = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 600000
    [void](Invoke-Redis @("ZADD", $normalExpiration, "$normalScore", $normalIntent))
    [void](Invoke-Redis @("ZADD", $normalGlobalExpiration, "$normalScore", $normalMember))

    Assert-Equal "normal reservation seed" (Invoke-Redis @("HEXISTS", $normalReservation, $normalIntent)) "1"
    Assert-Equal "normal owner seed" (Invoke-Redis @("HEXISTS", $normalOwner, "$normalSeatId")) "1"
    $normalInsert = "INSERT INTO d_reservation_transition_event (id,command_id,order_number,user_id,program_id,intent_id,target_sell_status,payload,event_status,retry_count,next_retry_time,last_attempt_time,last_error,create_time,edit_time,status) VALUES ($normalEventId,$normalCommandId,$normalOrderNumber,$normalUserId,$normalProgramId,'$normalIntent',1,'{}','PENDING',0,NOW(),NULL,NULL,NOW(),NOW(),1)"
    [void](Invoke-TradeSql $normalInsert)
    $normalEvent = Wait-Transition $normalEventId @("SUCCEEDED")

    Assert-Equal "normal final state" (Invoke-Redis @("HGET", $normalFinal, $normalIntent)) "RELEASED"
    Assert-Equal "normal owner removed" (Invoke-Redis @("HEXISTS", $normalOwner, "$normalSeatId")) "0"
    Assert-Equal "normal seat restored" (Invoke-Redis @("ZSCORE", $normalAvailable, "$normalSeatId")) "1000007"
    Assert-Equal "normal reservation removed" (Invoke-Redis @("HEXISTS", $normalReservation, $normalIntent)) "0"
    Assert-Equal "normal account count removed" (Invoke-Redis @("HGET", $normalAccount, "$normalUserId")) ""
    Assert-Equal "normal global expiration removed" (Invoke-Redis @("ZSCORE", $normalGlobalExpiration, $normalMember)) ""
    Write-Output "NORMAL_RELEASE=PASS event=$normalEvent"

    [void](Invoke-Redis @("HSET", $corruptMeta, "$normalSeatId", '{"rowCode":1,"colCode":7}'))
    [void](Invoke-Redis @("HSET", $corruptOwner, "$normalSeatId", $corruptIntent))
    $corruptReservationJson = "{`"userId`":$normalUserId,`"ticketCount`":1,`"seats`":[{`"seatId`":$normalSeatId,`"ticketCategoryId`":$normalCategoryId}]}"
    [void](Invoke-Redis @("HSET", $corruptReservation, $corruptIntent, $corruptReservationJson))
    $corruptScore = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 600000
    [void](Invoke-Redis @("ZADD", $corruptExpiration, "$corruptScore", $corruptIntent))
    [void](Invoke-Redis @("ZADD", $corruptGlobalExpiration, "$corruptScore", $corruptMember))
    $corruptInsert = "INSERT INTO d_reservation_transition_event (id,command_id,order_number,user_id,program_id,intent_id,target_sell_status,payload,event_status,retry_count,next_retry_time,last_attempt_time,last_error,create_time,edit_time,status) VALUES ($corruptEventId,$corruptCommandId,$corruptOrderNumber,$normalUserId,$corruptProgramId,'$corruptIntent',1,'{}','PENDING',0,NOW(),NULL,NULL,NOW(),NOW(),1)"
    [void](Invoke-TradeSql $corruptInsert)
    $corruptEvent = Wait-Transition $corruptEventId @("FAILED")

    Assert-Equal "corrupt reservation retained" (Invoke-Redis @("HEXISTS", $corruptReservation, $corruptIntent)) "1"
    Assert-Equal "corrupt final state absent" (Invoke-Redis @("HGET", $corruptFinal, $corruptIntent)) ""
    if ($corruptEvent -notlike "*OWNER_MISMATCH*") {
        throw "corrupt transition did not expose OWNER_MISMATCH: $corruptEvent"
    }
    Write-Output "CORRUPTION_GUARD=PASS event=$corruptEvent"
}
finally {
    Remove-SyntheticScenario $normalEventId $normalCommandId $normalOrderNumber $normalKeys $normalGlobalExpiration $normalMember
    Remove-SyntheticScenario $corruptEventId $corruptCommandId $corruptOrderNumber $corruptKeys $corruptGlobalExpiration $corruptMember
    Write-Output "SYNTHETIC_DATA_CLEANED=PASS"
}
