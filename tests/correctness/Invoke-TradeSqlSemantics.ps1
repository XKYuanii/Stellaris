param(
    [string]$MySqlContainer = 'stellaris-local-mysql-1',
    [string]$MySqlPassword = 'mysql123',
    [string]$SchemaPath = (Join-Path $PSScriptRoot '..\..\sql\reliability\20260910_single_trade_stream_schema.sql')
)

$ErrorActionPreference = 'Stop'
$programId = 990000001L
$categoryId = 990000011L
$userId = 990000021L
$otherUserId = 990000022L
$seatOne = 990000031L
$seatTwo = 990000032L
$orderOne = 990000041L
$orderTwo = 990000042L

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)
    if ("$Expected" -ne "$Actual") {
        throw "$Message expected=$Expected actual=$Actual"
    }
}

function Invoke-MySql {
    param([string]$Query)
    $result = & docker exec $MySqlContainer mysql -uroot "--password=$MySqlPassword" --batch --skip-column-names --raw -e $Query 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL query failed: $Query"
    }
    return @($result)
}

function Invoke-TradeScalar {
    param([string]$Query)
    $values = @(Invoke-MySql -Query "USE stellaris_trade; $Query")
    if ($values.Count -eq 0) { return '' }
    return "$($values[-1])"
}

function Invoke-AffectedRows {
    param([string]$Statement)
    return [int](Invoke-TradeScalar -Query "$Statement; SELECT ROW_COUNT();")
}

$running = & docker inspect -f '{{.State.Running}}' $MySqlContainer 2>$null
if ($LASTEXITCODE -ne 0 -or $running -ne 'true') {
    throw "Container is not running: $MySqlContainer"
}

if (-not (Test-Path -LiteralPath $SchemaPath)) {
    throw "Schema file does not exist: $SchemaPath"
}

Write-Host 'Applying idempotent trade schema...'
Get-Content -LiteralPath $SchemaPath -Raw |
    & docker exec -i $MySqlContainer mysql -uroot "--password=$MySqlPassword" 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Trade schema application failed' }

function Clear-Fixture {
    Invoke-MySql -Query @"
USE stellaris_trade;
DELETE FROM t_order_request WHERE program_id = $programId OR order_number IN ($orderOne, $orderTwo);
DELETE FROM t_account_program_purchase WHERE program_id = $programId;
DELETE FROM t_seat_inventory WHERE program_id = $programId;
"@ | Out-Null
}

Clear-Fixture
try {
    Write-Host 'Checking INSERT IGNORE idempotency keys...'
    $insertRequest = @"
INSERT IGNORE INTO t_order_request
(reservation_id, program_id, user_id, request_id, order_number, request_fingerprint,
 result_status, reject_code, created_at, updated_at)
VALUES ('sql-reservation-a', $programId, $userId, 'sql-request-a', $orderOne,
        'fingerprint-a', 'PROCESSING', NULL, NOW(), NOW())
"@
    Assert-Equal 1 (Invoke-AffectedRows -Statement $insertRequest) 'First request must acquire processing ownership'
    Assert-Equal 0 (Invoke-AffectedRows -Statement $insertRequest) 'Same reservation must be ignored'
    Assert-Equal 0 (Invoke-AffectedRows -Statement @"
INSERT IGNORE INTO t_order_request
(reservation_id, program_id, user_id, request_id, order_number, request_fingerprint,
 result_status, reject_code, created_at, updated_at)
VALUES ('sql-reservation-b', $programId, $userId, 'sql-request-a', $orderTwo,
        'fingerprint-b', 'PROCESSING', NULL, NOW(), NOW())
"@) 'Same user requestId must be ignored'
    Assert-Equal 0 (Invoke-AffectedRows -Statement @"
INSERT IGNORE INTO t_order_request
(reservation_id, program_id, user_id, request_id, order_number, request_fingerprint,
 result_status, reject_code, created_at, updated_at)
VALUES ('sql-reservation-c', $programId, $otherUserId, 'sql-request-c', $orderOne,
        'fingerprint-c', 'PROCESSING', NULL, NOW(), NOW())
"@) 'Same orderNumber must be ignored'
    Assert-Equal 1 (Invoke-TradeScalar -Query "SELECT COUNT(*) FROM t_order_request WHERE program_id=$programId;") 'Only one request row may exist'

    Write-Host 'Checking account limit boundary and unlimited semantics...'
    Assert-Equal 1 (Invoke-AffectedRows -Statement "INSERT IGNORE INTO t_account_program_purchase (program_id,user_id,purchase_count,updated_at) VALUES ($programId,$userId,0,NOW())") 'Purchase counter must initialize'
    Assert-Equal 1 (Invoke-AffectedRows -Statement "UPDATE t_account_program_purchase SET purchase_count=purchase_count+2,updated_at=NOW() WHERE program_id=$programId AND user_id=$userId AND (2<=0 OR purchase_count+2<=3)") 'Count below limit must succeed'
    Assert-Equal 0 (Invoke-AffectedRows -Statement "UPDATE t_account_program_purchase SET purchase_count=purchase_count+2,updated_at=NOW() WHERE program_id=$programId AND user_id=$userId AND (3<=0 OR purchase_count+2<=3)") 'Count above limit must fail'
    Assert-Equal 1 (Invoke-AffectedRows -Statement "UPDATE t_account_program_purchase SET purchase_count=purchase_count+4,updated_at=NOW() WHERE program_id=$programId AND user_id=$userId AND (0<=0 OR purchase_count+4<=0)") 'Zero limit must mean unlimited'
    Assert-Equal 6 (Invoke-TradeScalar -Query "SELECT purchase_count FROM t_account_program_purchase WHERE program_id=$programId AND user_id=$userId;") 'Unlimited update must still record purchases'

    Write-Host 'Checking snapshot upsert and seat CAS...'
    $insertSeats = @"
INSERT INTO t_seat_inventory
(program_id,seat_id,ticket_category_id,sell_status,reservation_id,order_number,
 version,price_in_cents,sale_version,create_time,edit_time,status)
VALUES
($programId,$seatOne,$categoryId,1,NULL,NULL,0,10000,'sale-v1',NOW(),NOW(),1),
($programId,$seatTwo,$categoryId,1,NULL,NULL,0,10000,'sale-v1',NOW(),NOW(),1)
ON DUPLICATE KEY UPDATE
 ticket_category_id=IF(sell_status=1,VALUES(ticket_category_id),ticket_category_id),
 price_in_cents=IF(sell_status=1,VALUES(price_in_cents),price_in_cents),
 sale_version=IF(sell_status=1,VALUES(sale_version),sale_version),
 edit_time=NOW()
"@
    Invoke-AffectedRows -Statement $insertSeats | Out-Null
    Assert-Equal 0 (Invoke-AffectedRows -Statement "UPDATE t_seat_inventory SET sell_status=2,reservation_id='sql-reservation-a',order_number=$orderOne,version=version+1,edit_time=NOW() WHERE program_id=$programId AND seat_id=$seatOne AND ticket_category_id=$categoryId AND price_in_cents=9999 AND sale_version='sale-v1' AND sell_status=1 AND reservation_id IS NULL AND order_number IS NULL AND status=1") 'Wrong price must fail seat CAS'
    Assert-Equal 1 (Invoke-AffectedRows -Statement "UPDATE t_seat_inventory SET sell_status=2,reservation_id='sql-reservation-a',order_number=$orderOne,version=version+1,edit_time=NOW() WHERE program_id=$programId AND seat_id=$seatOne AND ticket_category_id=$categoryId AND price_in_cents=10000 AND sale_version='sale-v1' AND sell_status=1 AND reservation_id IS NULL AND order_number IS NULL AND status=1") 'Matching snapshot must lock seat'
    Assert-Equal 0 (Invoke-AffectedRows -Statement "UPDATE t_seat_inventory SET sell_status=2,reservation_id='sql-reservation-a',order_number=$orderOne,version=version+1,edit_time=NOW() WHERE program_id=$programId AND seat_id=$seatOne AND ticket_category_id=$categoryId AND price_in_cents=10000 AND sale_version='sale-v1' AND sell_status=1 AND reservation_id IS NULL AND order_number IS NULL AND status=1") 'Already locked seat must reject duplicate CAS'

    Invoke-AffectedRows -Statement @"
INSERT INTO t_seat_inventory
(program_id,seat_id,ticket_category_id,sell_status,reservation_id,order_number,
 version,price_in_cents,sale_version,create_time,edit_time,status)
VALUES ($programId,$seatOne,$categoryId,1,NULL,NULL,0,12000,'sale-v2',NOW(),NOW(),1)
ON DUPLICATE KEY UPDATE
 ticket_category_id=IF(sell_status=1,VALUES(ticket_category_id),ticket_category_id),
 price_in_cents=IF(sell_status=1,VALUES(price_in_cents),price_in_cents),
 sale_version=IF(sell_status=1,VALUES(sale_version),sale_version),
 edit_time=NOW()
"@ | Out-Null
    Assert-Equal '2,10000,sale-v1' (Invoke-TradeScalar -Query "SELECT CONCAT_WS(',',sell_status,price_in_cents,sale_version) FROM t_seat_inventory WHERE program_id=$programId AND seat_id=$seatOne;") 'Snapshot refresh must not overwrite a locked seat'
    Invoke-AffectedRows -Statement @"
INSERT INTO t_seat_inventory
(program_id,seat_id,ticket_category_id,sell_status,reservation_id,order_number,
 version,price_in_cents,sale_version,create_time,edit_time,status)
VALUES ($programId,$seatTwo,$categoryId,1,NULL,NULL,0,12000,'sale-v2',NOW(),NOW(),1)
ON DUPLICATE KEY UPDATE
 ticket_category_id=IF(sell_status=1,VALUES(ticket_category_id),ticket_category_id),
 price_in_cents=IF(sell_status=1,VALUES(price_in_cents),price_in_cents),
 sale_version=IF(sell_status=1,VALUES(sale_version),sale_version),
 edit_time=NOW()
"@ | Out-Null
    Assert-Equal '1,12000,sale-v2' (Invoke-TradeScalar -Query "SELECT CONCAT_WS(',',sell_status,price_in_cents,sale_version) FROM t_seat_inventory WHERE program_id=$programId AND seat_id=$seatTwo;") 'Snapshot refresh must update an available seat'

    Write-Host 'Checking all-or-nothing rollback for a multi-seat lock...'
    $rollbackRows = @(Invoke-MySql -Query @"
USE stellaris_trade;
UPDATE t_seat_inventory SET sell_status=1,reservation_id=NULL,order_number=NULL,price_in_cents=10000,sale_version='sale-v1' WHERE program_id=$programId;
START TRANSACTION;
UPDATE t_seat_inventory SET sell_status=2,reservation_id='sql-rollback',order_number=$orderTwo,version=version+1
 WHERE program_id=$programId AND seat_id=$seatOne AND price_in_cents=10000 AND sale_version='sale-v1' AND sell_status=1 AND reservation_id IS NULL AND order_number IS NULL;
SELECT ROW_COUNT();
UPDATE t_seat_inventory SET sell_status=2,reservation_id='sql-rollback',order_number=$orderTwo,version=version+1
 WHERE program_id=$programId AND seat_id=$seatTwo AND price_in_cents=10000 AND sale_version='wrong-version' AND sell_status=1 AND reservation_id IS NULL AND order_number IS NULL;
SELECT ROW_COUNT();
ROLLBACK;
"@)
    Assert-Equal 1 $rollbackRows[0] 'First seat in transaction must lock'
    Assert-Equal 0 $rollbackRows[1] 'Second stale seat must fail'
    Assert-Equal 2 (Invoke-TradeScalar -Query "SELECT COUNT(*) FROM t_seat_inventory WHERE program_id=$programId AND sell_status=1 AND reservation_id IS NULL AND order_number IS NULL;") 'Rollback must restore every seat'

    [pscustomobject]@{
        schema = 'PASS'
        requestUniqueKeys = 'PASS'
        accountLimitBoundary = 'PASS'
        snapshotUpsert = 'PASS'
        seatCas = 'PASS'
        multiSeatRollback = 'PASS'
    } | Format-List
}
finally {
    Clear-Fixture
}
