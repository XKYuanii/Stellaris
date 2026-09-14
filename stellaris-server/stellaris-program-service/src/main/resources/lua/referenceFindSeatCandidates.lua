-- Read one deterministic candidate window and its sale version in one Redis round trip.
-- All KEYS share the same {sale:n} hash tag, so this script is Redis Cluster safe.
-- KEYS: ready, maintenance, available-zset, seat-meta-hash, sale-version
-- ARGV: requested-count, candidate-limit, request-hash, target-groups, page-size

local requested_count = tonumber(ARGV[1])
local candidate_limit = tonumber(ARGV[2])
local request_hash = tonumber(ARGV[3])
local target_groups = tonumber(ARGV[4])
local page_size = tonumber(ARGV[5])

local function result(status, seats)
    return cjson.encode({
        status = status,
        saleVersion = redis.call('GET', KEYS[5]) or '',
        seats = seats or {}
    })
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return result('NOT_READY')
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    return result('MAINTENANCE')
end

local available_count = redis.call('ZCARD', KEYS[3])
if available_count < requested_count then
    return result('INSUFFICIENT')
end

local inspect_limit = math.min(available_count, candidate_limit)
local start_rank = request_hash % available_count
local inspected = 0
local candidates = {}
local previous_row = nil
local previous_column = nil
local consecutive = 0
local group_count = 0

while inspected < inspect_limit and group_count < target_groups do
    local requested_page = math.min(page_size, inspect_limit - inspected)
    local rank = (start_rank + inspected) % available_count
    local first_size = math.min(requested_page, available_count - rank)
    local ids = redis.call('ZRANGE', KEYS[3], rank, rank + first_size - 1)
    local remaining = requested_page - #ids
    if remaining > 0 then
        local wrapped = redis.call('ZRANGE', KEYS[3], 0, remaining - 1)
        for _, id in ipairs(wrapped) do
            table.insert(ids, id)
        end
    end
    if #ids == 0 then
        break
    end

    local metadata = redis.call('HMGET', KEYS[4], unpack(ids))
    for _, raw in ipairs(metadata) do
        if not raw then
            return result('METADATA_MISSING')
        end
        local seat = cjson.decode(raw)
        local row = tonumber(seat.rowCode)
        local column = tonumber(seat.colCode)
        if not row or not column then
            return result('METADATA_MISSING')
        end
        table.insert(candidates, seat)
        if previous_row == row and previous_column and column == previous_column + 1 then
            consecutive = consecutive + 1
        else
            consecutive = 1
        end
        if consecutive >= requested_count then
            group_count = group_count + 1
        end
        previous_row = row
        previous_column = column
    end
    inspected = inspected + #ids
    if #ids < requested_page then
        break
    end
end

return result('OK', candidates)
