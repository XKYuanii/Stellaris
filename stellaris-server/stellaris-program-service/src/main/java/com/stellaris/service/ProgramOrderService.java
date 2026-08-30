package com.stellaris.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.client.OrderClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.domain.PurchaseSeat;
import com.stellaris.dto.DelayOrderCancelDto;
import com.stellaris.dto.OrderCreateDto;
import com.stellaris.dto.OrderTicketUserCreateDto;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.SeatDto;
import com.stellaris.entity.ProgramRecordTask;
import com.stellaris.entity.ProgramShowTime;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.OrderStatus;
import com.stellaris.enums.RecordType;
import com.stellaris.enums.SellStatus;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.ProgramRecordTaskMapper;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.service.delaysend.DelayOrderCancelSend;
import com.stellaris.service.domain.CreateOrderTemporaryData;
import com.stellaris.service.kafka.CreateOrderSend;
import com.stellaris.service.lua.ProgramCacheCreateOrderData;
import com.stellaris.service.lua.ProgramCacheCreateOrderResolutionOperate;
import com.stellaris.service.lua.ProgramCacheResolutionOperate;
import com.stellaris.service.tool.SeatMatch;
import com.stellaris.util.DateUtils;
import com.stellaris.vo.ProgramVo;
import com.stellaris.vo.SeatVo;
import com.stellaris.vo.TicketCategoryVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.stellaris.constant.Constant.GLIDE_LINE;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单 service
 * @author: xz_y
 **/
@Slf4j
@Service
public class ProgramOrderService {

    /** 订单服务客户端，用于通过 RPC 调用订单服务创建正式订单。 */
    @Autowired
    private OrderClient orderClient;

    /** 分布式 ID 生成器，用于生成订单号、库存操作记录 ID 和任务 ID。 */
    @Autowired
    private UidGenerator uidGenerator;

    /**
     * 节目缓存通用操作器。
     * 负责在“创建订单”和“取消订单”场景下，原子地迁移座位状态并增减余票。
     */
    @Autowired
    private ProgramCacheResolutionOperate programCacheResolutionOperate;

    /**
     * 创建订单专用的缓存操作器。
     * 内部通过 Lua 脚本完成余票校验、余票扣减和座位锁定，避免并发下出现超卖。
     */
    @Autowired
    ProgramCacheCreateOrderResolutionOperate programCacheCreateOrderResolutionOperate;

    /** 订单延迟取消消息发送器，用于处理超时未支付订单。 */
    @Autowired
    private DelayOrderCancelSend delayOrderCancelSend;

    /** 历史异步版本的 Kafka 发送器；最终 v5 使用 Redis Stream 中继。 */
    @Autowired
    private CreateOrderSend createOrderSend;

    /** 节目服务，用于查询组装订单所需的节目基础信息。 */
    @Autowired
    private ProgramService programService;

    /** 节目场次服务，用于查询演出时间。 */
    @Autowired
    private ProgramShowTimeService programShowTimeService;

    /** 票档服务，用于查询票档信息和 Redis 中的票档余量。 */
    @Autowired
    private TicketCategoryService ticketCategoryService;

    /** 座位服务，用于查询并初始化节目各票档的座位缓存。 */
    @Autowired
    private SeatService seatService;

    /** 节目库存记录任务 Mapper，用于持久化库存记录处理任务。 */
    @Autowired
    private ProgramRecordTaskMapper programRecordTaskMapper;

    /**
     * 获取本次下单实际涉及的票档。
     *
     * <p>处理规则：</p>
     * <ol>
     *     <li>先按节目 ID 查询当前可用的全部票档，并转换为以票档 ID 为 key 的 Map；</li>
     *     <li>如果请求中包含具体座位，则逐个读取座位所属票档；</li>
     *     <li>如果请求中没有具体座位，则读取请求中指定的单个票档；</li>
     *     <li>请求中的票档不属于当前节目时，立即终止下单。</li>
     * </ol>
     *
     * @param programOrderCreateDto 创建节目订单的请求参数
     * @param showTime              节目演出时间，供票档多级缓存查询设置有效期
     * @return 本次订单涉及的票档列表
     * @throws StellarisFrameException 请求中的票档不存在时抛出
     */
    public List<TicketCategoryVo> getTicketCategoryList(ProgramOrderCreateDto programOrderCreateDto, Date showTime){
        // 保存经过校验、确实属于当前节目的票档。
        List<TicketCategoryVo> getTicketCategoryVoList = new ArrayList<>();

        // 从多级缓存查询当前节目的全部票档；缓存有效期与演出时间相关。
        List<TicketCategoryVo> ticketCategoryVoList =
                ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(programOrderCreateDto.getProgramId(),
                        showTime);

        // 转为 Map 后可根据票档 ID 进行 O(1) 查询，避免每个座位都遍历票档列表。
        Map<Long, TicketCategoryVo> ticketCategoryVoMap =
                ticketCategoryVoList.stream()
                        .collect(Collectors.toMap(TicketCategoryVo::getId, ticketCategoryVo -> ticketCategoryVo));
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();

        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            // 用户手动选座时，一个订单可能包含多个票档，因此逐个校验座位对应的票档。
            for (SeatDto seatDto : seatDtoList) {
                TicketCategoryVo ticketCategoryVo = ticketCategoryVoMap.get(seatDto.getTicketCategoryId());
                if (Objects.nonNull(ticketCategoryVo)) {
                    getTicketCategoryVoList.add(ticketCategoryVo);
                }else {
                    // 防止客户端提交其他节目或不存在的票档 ID。
                    throw new StellarisFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2);
                }
            }
        } else {
            // 自动配座只涉及请求中明确指定的一个票档。
            TicketCategoryVo ticketCategoryVo = ticketCategoryVoMap.get(programOrderCreateDto.getTicketCategoryId());
            if (Objects.nonNull(ticketCategoryVo)) {
                getTicketCategoryVoList.add(ticketCategoryVo);
            }else {
                throw new StellarisFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2);
            }
        }
        return getTicketCategoryVoList;
    }
    
    /**
     * 创建节目订单（旧版同步缓存处理流程）。
     *
     * <p>整体流程：</p>
     * <ol>
     *     <li>查询节目场次和本次下单涉及的票档；</li>
     *     <li>查询各票档的可售座位以及 Redis 剩余票数；</li>
     *     <li>根据请求是否包含具体座位，进入“用户选座”或“系统自动配座”流程；</li>
     *     <li>校验余票、座位状态和客户端价格；</li>
     *     <li>扣减余票，并把座位从未售出状态迁移为未支付锁定状态；</li>
     *     <li>同步调用订单服务创建订单，并发送超时取消消息。</li>
     * </ol>
     *
     * @param programOrderCreateDto 创建订单请求参数
     * @param orderVersion          订单版本，用于区分订单服务中的不同创建逻辑
     * @return 创建成功后的订单编号
     */
    public String create(ProgramOrderCreateDto programOrderCreateDto,Integer orderVersion) {
        // 查询节目场次；演出时间用于计算座位缓存距离失效还剩多少秒。
        ProgramShowTime programShowTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(programOrderCreateDto.getProgramId());

        // 获取并校验本次订单涉及的票档。手动选座可能涉及多个票档，自动配座只涉及一个票档。
        List<TicketCategoryVo> getTicketCategoryList = 
                getTicketCategoryList(programOrderCreateDto,programShowTime.getShowTime());

        // 分别累计客户端提交价格和服务端可信价格，稍后用于防篡改校验。
        BigDecimal parameterOrderPrice = new BigDecimal("0");
        BigDecimal databaseOrderPrice = new BigDecimal("0");

        // 最终确定需要锁定并用于创建订单的座位。
        List<SeatVo> purchaseSeatList = new ArrayList<>();

        // 非空表示用户指定了具体座位；为空表示需要系统按票档和数量自动配座。
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();

        // 汇总本次订单涉及票档下的全部未售出座位。
        List<SeatVo> seatVoList = new ArrayList<>();

        // key 为票档 ID 字符串，value 为 Redis 中记录的该票档剩余票数。
        Map<String, Long> ticketCategoryRemainNumber = new HashMap<>(16);

        for (TicketCategoryVo ticketCategory : getTicketCategoryList) {
            /*
             * 查询指定票档的全部座位。若缓存不存在，座位服务会从数据库加载后写入缓存；
             * 缓存时间设置为当前时刻到节目开场之间的秒数，避免节目结束后仍长期保留热点数据。
             */
            List<SeatVo> allSeatVoList = 
                    seatService.selectSeatResolution(programOrderCreateDto.getProgramId(), ticketCategory.getId(), 
                            DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS);

            // 只保留未售出座位；已锁定或已售出的座位不能参与本次选择。
            seatVoList.addAll(allSeatVoList.stream().
                    filter(seatVo -> seatVo.getSellStatus().equals(SellStatus.NO_SOLD.getCode())).toList());

            // 同时读取票档余量，后续先做一次快速的余票数量校验。
            ticketCategoryRemainNumber.putAll(ticketCategoryService.getRedisRemainNumberResolution(
                    programOrderCreateDto.getProgramId(),ticketCategory.getId()));
        }

        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            /*
             * 用户手动选座：按票档统计购买数量。
             * 例如同一订单在票档 A 选择 2 个座位、票档 B 选择 1 个座位，
             * 得到的 Map 为 {A -> 2, B -> 1}。
             */
            Map<Long, Long> seatTicketCategoryDtoCount = seatDtoList.stream()
                    .collect(Collectors.groupingBy(SeatDto::getTicketCategoryId, Collectors.counting()));

            for (Entry<Long, Long> entry : seatTicketCategoryDtoCount.entrySet()) {
                Long ticketCategoryId = entry.getKey();
                Long purchaseCount = entry.getValue();

                // Redis 余票 Map 中没有对应票档，说明票档不存在或缓存数据异常。
                Long remainNumber = Optional.ofNullable(ticketCategoryRemainNumber.get(String.valueOf(ticketCategoryId)))
                        .orElseThrow(() -> new StellarisFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2));

                // 当前票档需要购买的座位数不能超过剩余票数。
                if (purchaseCount > remainNumber) {
                    throw new StellarisFrameException(BaseCode.TICKET_REMAIN_NUMBER_NOT_SUFFICIENT);
                }
            }

            /*
             * 将可售座位转换为“行号-列号 -> 座位”的 Map，方便快速定位用户选择的座位。
             * 合并函数 (v1, v2) -> v2 用于在 key 重复时保留后一个值。
             */
            Map<String, SeatVo> seatVoMap = seatVoList.stream().collect(Collectors
                    .toMap(seat -> seat.getRowCode() + "-" + seat.getColCode(), seat -> seat, (v1, v2) -> v2));

            for (SeatDto seatDto : seatDtoList) {
                // 只从“未售出座位 Map”中查找，所以查不到也可能表示座位已被其他订单占用。
                SeatVo seatVo = seatVoMap.get(seatDto.getRowCode() + "-" + seatDto.getColCode());
                if (Objects.isNull(seatVo)) {
                    throw new StellarisFrameException(BaseCode.SEAT_IS_NOT_NOT_SOLD);
                }

                purchaseSeatList.add(seatVo);

                // BigDecimal 是不可变对象，add 后必须接收返回值。
                parameterOrderPrice = parameterOrderPrice.add(seatDto.getPrice());
                databaseOrderPrice = databaseOrderPrice.add(seatVo.getPrice());
            }

            /*
             * 对比客户端提交总价与服务端座位总价。
             * 此处沿用原业务规则：客户端总价高于服务端总价时判定价格错误。
             */
            if (parameterOrderPrice.compareTo(databaseOrderPrice) > 0) {
                throw new StellarisFrameException(BaseCode.PRICE_ERROR);
            }
        }else {
            // 系统自动配座：客户端只提交票档 ID 和购票数量，没有提交具体行列号。
            Long ticketCategoryId = programOrderCreateDto.getTicketCategoryId();
            Integer ticketCount = programOrderCreateDto.getTicketCount();

            // 查询指定票档余量；不存在时按票档不存在处理。
            Long remainNumber = Optional.ofNullable(ticketCategoryRemainNumber.get(String.valueOf(ticketCategoryId)))
                    .orElseThrow(() -> new StellarisFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2));
            if (ticketCount > remainNumber) {
                throw new StellarisFrameException(BaseCode.TICKET_REMAIN_NUMBER_NOT_SUFFICIENT);
            }

            /*
             * 先筛选出指定票档的可售座位，再尽量匹配指定数量的相邻座位，
             * 让一次购买多张票的用户尽可能坐在一起。
             */
            purchaseSeatList = SeatMatch.findAdjacentSeatVos(seatVoList.stream().filter(seatVo ->
                    Objects.equals(seatVo.getTicketCategoryId(), ticketCategoryId)).collect(Collectors.toList()), ticketCount);

            // 总余票足够不代表存在足够的连续座位，因此还需校验实际匹配结果。
            if (purchaseSeatList.size() < ticketCount) {
                throw new StellarisFrameException(BaseCode.SEAT_OCCUPY);
            }
        }

        // 扣减票档余量，并把选中的座位从“未售出”迁移到“未支付锁定”。
        updateProgramCacheDataResolution(programOrderCreateDto.getProgramId(),purchaseSeatList,OrderStatus.NO_PAY);

        // 组装订单参数、同步创建正式订单，并发送超时取消消息。
        return doCreate(programOrderCreateDto,purchaseSeatList,orderVersion);
    }
    
    
    /**
     * 创建节目订单（Lua 原子操作 + RPC 同步创建订单）。
     *
     * <p>与 {@link #create(ProgramOrderCreateDto, Integer)} 相比，本方法把余票校验、
     * 余票扣减和座位锁定合并到一个 Lua 脚本中执行，缩小并发窗口并保证缓存操作的原子性。</p>
     *
     * @param programOrderCreateDto 创建订单请求参数
     * @param orderVersion          订单版本
     * @return 创建成功后的订单编号
     */
    public String createNew(ProgramOrderCreateDto programOrderCreateDto,Integer orderVersion) {
        // 原子操作 Redis，并取得库存操作记录 ID 及最终锁定的座位。
        CreateOrderTemporaryData createOrderTemporaryData = createOrderOperateProgramCacheResolution(programOrderCreateDto);

        // 同步创建订单的旧参数构建方法使用 SeatVo，因此在此转换座位对象类型。
        List<SeatVo> purchaseSeatList = createOrderTemporaryData.getPurchaseSeatList().stream().map(purchaseSeat -> {
            SeatVo seatVo = new SeatVo();
            BeanUtils.copyProperties(purchaseSeat,seatVo);
            return seatVo;
        }).collect(Collectors.toList());
        return doCreate(programOrderCreateDto,purchaseSeatList,orderVersion);
    }

    /**
     * 创建节目订单（Lua 原子操作 + Kafka 异步创建订单）。
     *
     * <p>本方法先在 Redis 中完成库存占用，再向 Kafka 发送创建订单消息。
     * 方法会等待 Kafka 发送结果，因此返回成功仅表示消息发送成功，实际订单由消费者异步落库。</p>
     *
     * @param programOrderCreateDto 创建订单请求参数
     * @param orderVersion          订单版本
     * @return 预先生成并发送到 Kafka 的订单编号
     */
    public String createNewAsync(ProgramOrderCreateDto programOrderCreateDto,Integer orderVersion) {
        // 先通过 Lua 原子扣减余票并锁定座位。
        CreateOrderTemporaryData createOrderTemporaryData = createOrderOperateProgramCacheResolution(programOrderCreateDto);
        // 再组装订单消息并发送 Kafka；发送失败时会回滚已占用的缓存库存。
        return doCreateV2(programOrderCreateDto,createOrderTemporaryData,orderVersion);
    }

    /**
     * 通过 Redis Lua 脚本原子完成创建订单前的库存操作。
     *
     * <p>脚本需要完成的核心工作包括：</p>
     * <ol>
     *     <li>校验各票档剩余票数；</li>
     *     <li>手动选座时校验并锁定指定座位，自动配座时选择可用座位；</li>
     *     <li>扣减对应票档余量；</li>
     *     <li>将座位从“未售出”缓存迁移到“锁定”缓存；</li>
     *     <li>写入库存扣减记录，供后续任务追踪和补偿。</li>
     * </ol>
     *
     * @param programOrderCreateDto 创建订单请求参数
     * @return 库存操作记录 ID 和脚本最终锁定的座位列表
     * @throws StellarisFrameException Lua 脚本返回非成功业务码时抛出
     */
    public CreateOrderTemporaryData createOrderOperateProgramCacheResolution(ProgramOrderCreateDto programOrderCreateDto){
        // 从多级缓存查询演出时间，供座位缓存初始化时计算过期时间。
        ProgramShowTime programShowTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(programOrderCreateDto.getProgramId());

        // 查询并校验本次请求涉及的票档。
        List<TicketCategoryVo> getTicketCategoryList =
                getTicketCategoryList(programOrderCreateDto,programShowTime.getShowTime());

        // 执行 Lua 前先确保相关座位和余票数据已经加载到 Redis。
        for (TicketCategoryVo ticketCategory : getTicketCategoryList) {
            // 缓存不存在时，座位服务会从数据库查询并回填缓存。
            seatService.selectSeatResolution(programOrderCreateDto.getProgramId(), ticketCategory.getId(),
                    DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS);
            // 缓存不存在时，票档服务会从数据库查询余票并回填缓存。
            ticketCategoryService.getRedisRemainNumberResolution(
                    programOrderCreateDto.getProgramId(),ticketCategory.getId());
        }

        Long programId = programOrderCreateDto.getProgramId();
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();

        /*
         * Lua 脚本的 KEYS 参数。
         * 本项目除 Redis key 外，也通过该列表传递选座模式、节目 ID 和记录标识等控制参数。
         */
        List<String> keys = new ArrayList<>();

        // Lua 脚本的 ARGV 参数：票档更新数据、手动选座数据、购票人 ID 数据。
        String[] data = new String[3];

        // 每一项描述一个票档余量 key、票档 ID 和本次扣减数量。
        JSONArray jsonArray = new JSONArray();

        // 手动选座时，每一项描述票档的未售出座位 key 以及请求选择的座位。
        JSONArray addSeatDatajsonArray = new JSONArray();

        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            // “1”代表手动选座模式，Lua 脚本据此处理客户端指定的座位。
            keys.add("1");

            // 按票档分组，便于一次订单同时扣减多个票档库存。
            Map<Long, List<SeatDto>> seatTicketCategoryDtoCount = seatDtoList.stream()
                    .collect(Collectors.groupingBy(SeatDto::getTicketCategoryId));
            for (Entry<Long, List<SeatDto>> entry : seatTicketCategoryDtoCount.entrySet()) {
                Long ticketCategoryId = entry.getKey();
                int ticketCount = entry.getValue().size();

                // 组装当前票档的余票扣减指令。
                JSONObject jsonObject = new JSONObject();
                // 票档余量 Hash 的实际 Redis key。
                jsonObject.put("programTicketRemainNumberHashKey",RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, ticketCategoryId).getRelKey());
                jsonObject.put("ticketCategoryId",ticketCategoryId);
                jsonObject.put("ticketCount",ticketCount);
                jsonArray.add(jsonObject);

                // 组装当前票档的指定座位锁定指令。
                JSONObject seatDatajsonObject = new JSONObject();
                // 未售出座位 Hash 的实际 Redis key。
                seatDatajsonObject.put("seatNoSoldHashKey",RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
                // 序列化该票档下客户端选择的全部座位。
                seatDatajsonObject.put("seatDataList",JSON.toJSONString(entry.getValue()));
                addSeatDatajsonArray.add(seatDatajsonObject);
            }
        }else {
            // “2”代表自动配座模式，Lua 脚本根据票档和数量选择座位。
            keys.add("2");
            Long ticketCategoryId = programOrderCreateDto.getTicketCategoryId();
            Integer ticketCount = programOrderCreateDto.getTicketCount();

            // 自动配座只涉及一个票档，将余量 key、扣减数量和座位 Hash key 放在同一指令中。
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("programTicketRemainNumberHashKey",RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, ticketCategoryId).getRelKey());
            jsonObject.put("ticketCategoryId",ticketCategoryId);
            jsonObject.put("ticketCount",ticketCount);
            jsonObject.put("seatNoSoldHashKey",RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, ticketCategoryId).getRelKey());
            jsonArray.add(jsonObject);
        }

        /*
         * 以下 key 使用带占位符的模板，Lua 脚本会结合节目 ID、票档 ID 生成实际 key，
         * 从而在同一个脚本中处理多个票档。
         */
        // 未售出座位 Hash key 模板。
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH));
        // 已锁定座位 Hash key 模板。
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH));
        // 当前节目 ID。
        keys.add(String.valueOf(programOrderCreateDto.getProgramId()));
        // 库存操作记录 key 模板。
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_RECORD));

        // 为本次库存扣减生成全局唯一标识，后续可用于追踪和补偿。
        Long identifierId = uidGenerator.getUid();

        // 记录字段由操作类型、记录 ID、用户 ID 组成，GLIDE_LINE 为项目约定的分隔符。
        keys.add(RecordType.REDUCE.getValue() + GLIDE_LINE + identifierId + GLIDE_LINE + programOrderCreateDto.getUserId());
        // 明确本次库存记录属于扣减操作。
        keys.add(RecordType.REDUCE.getValue());

        // 将结构化参数序列化后传给 Lua 脚本。
        data[0] = JSON.toJSONString(jsonArray);
        data[1] = JSON.toJSONString(addSeatDatajsonArray);
        // 购票人 ID 与最终座位一一关联，由脚本写入返回的 PurchaseSeat。
        data[2] = JSON.toJSONString(programOrderCreateDto.getTicketUserIdList().stream()
                .map(String::valueOf)
                .toList());

        // 单次执行 Lua 脚本，保证校验、扣库存、锁座和写记录不可被其他请求插入。
        ProgramCacheCreateOrderData programCacheCreateOrderData =
                programCacheCreateOrderResolutionOperate.programCacheOperate(keys, data);

        // 将 Lua 返回的业务错误码转换为统一业务异常。
        if (!Objects.equals(programCacheCreateOrderData.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new StellarisFrameException(Objects.requireNonNull(BaseCode.getRc(programCacheCreateOrderData.getCode())));
        }

        // 返回操作标识及脚本实际锁定的座位，后续用于创建订单或失败补偿。
        return new CreateOrderTemporaryData(identifierId,programCacheCreateOrderData.getPurchaseSeatList());
    }
    
    /**
     * 同步创建订单并安排超时取消。
     *
     * @param programOrderCreateDto 原始创建订单请求
     * @param purchaseSeatList      已校验且已在缓存中锁定的座位
     * @param orderVersion          订单版本
     * @return 订单服务返回的订单编号
     */
    private String doCreate(ProgramOrderCreateDto programOrderCreateDto,List<SeatVo> purchaseSeatList,Integer orderVersion){
        // 将节目、座位和购票人信息组装为订单服务所需参数。
        OrderCreateDto orderCreateDto = buildCreateOrderParam(programOrderCreateDto, purchaseSeatList, orderVersion);

        // 通过 RPC 同步创建订单；调用失败时方法内部会释放缓存中锁定的座位。
        String orderNumber = createOrderByRpc(orderCreateDto,purchaseSeatList);

        // 订单创建成功后发送延迟消息，支付超时后由消费者取消订单并释放库存。
        DelayOrderCancelDto delayOrderCancelDto = new DelayOrderCancelDto();
        delayOrderCancelDto.setProgramId(programOrderCreateDto.getProgramId());
        delayOrderCancelDto.setOrderNumber(orderCreateDto.getOrderNumber());
        delayOrderCancelSend.sendMessage(delayOrderCancelDto);

        return orderNumber;
    }

    /**
     * 通过 Kafka 异步创建订单并安排超时取消。
     *
     * @param programOrderCreateDto   原始创建订单请求
     * @param createOrderTemporaryData Lua 脚本返回的库存操作记录及锁座结果
     * @param orderVersion            订单版本
     * @return 发送到 Kafka 的订单编号
     */
    private String doCreateV2(ProgramOrderCreateDto programOrderCreateDto,
                               CreateOrderTemporaryData createOrderTemporaryData,
                               Integer orderVersion){
        // Lua 返回的 PurchaseSeat 已包含购票人与座位的绑定关系，可直接构建 V2 订单参数。
        OrderCreateDto orderCreateDto = buildCreateOrderParamV2(programOrderCreateDto.getProgramId(),
                programOrderCreateDto.getUserId(), createOrderTemporaryData.getPurchaseSeatList(),orderVersion);

        // 将通用订单 DTO 复制为 Kafka 消息对象，并附带本次库存操作记录 ID。
        OrderCreateMq orderCreateMq = new OrderCreateMq();
        BeanUtils.copyProperties(orderCreateDto,orderCreateMq);
        orderCreateMq.setIdentifierId(createOrderTemporaryData.getIdentifierId());

        // 先持久化可靠事件，再发送 Kafka；发送失败由后台中继继续投递。
        String orderNumber = createOrderByMq(orderCreateMq,createOrderTemporaryData.getPurchaseSeatList());

        // 消息发送成功后安排订单支付超时取消任务。
        DelayOrderCancelDto delayOrderCancelDto = new DelayOrderCancelDto();
        delayOrderCancelDto.setProgramId(orderCreateDto.getProgramId());
        delayOrderCancelDto.setOrderNumber(orderCreateDto.getOrderNumber());
        delayOrderCancelSend.sendMessage(delayOrderCancelDto);
        
        return orderNumber;
    }

    /**
     * 创建节目库存记录处理任务。
     *
     * <p>该任务与 Lua 写入的库存操作记录配合使用，使后台任务能够按节目扫描并处理记录。</p>
     *
     * @param programId 节目 ID
     */
    public void createProgramRecordTask(Long programId){
        ProgramRecordTask programRecordTask = new ProgramRecordTask();
        programRecordTask.setId(uidGenerator.getUid());
        programRecordTask.setProgramId(programId);
        // 创建时间和最后编辑时间在首次插入时保持一致。
        programRecordTask.setCreateTime(DateUtils.now());
        programRecordTask.setEditTime(DateUtils.now());
        programRecordTaskMapper.insert(programRecordTask);
    }

    /**
     * 组装同步创建订单所需参数。
     *
     * <p>订单总价始终根据服务端已确认的座位价格重新计算；随后按请求中的购票人顺序，
     * 将第 i 个购票人与第 i 个座位绑定，生成订单票人明细。</p>
     *
     * @param programOrderCreateDto 创建订单请求
     * @param purchaseSeatList      最终购买座位列表
     * @param orderVersion          订单版本
     * @return 完整的订单创建参数
     */
    private OrderCreateDto buildCreateOrderParam(ProgramOrderCreateDto programOrderCreateDto,
                                                  List<SeatVo> purchaseSeatList,
                                                  Integer orderVersion){
        return buildCreateOrderParam(programOrderCreateDto, purchaseSeatList, orderVersion,
                uidGenerator.getOrderNumber(programOrderCreateDto.getUserId()));
    }

    private OrderCreateDto buildCreateOrderParam(ProgramOrderCreateDto programOrderCreateDto,
                                                  List<SeatVo> purchaseSeatList,
                                                  Integer orderVersion,
                                                  Long orderNumber){
        // 查询订单快照所需的节目标题、图片、场地、演出时间等信息。
        ProgramVo programVo = programService.simpleGetProgramAndShowMultipleCache(programOrderCreateDto.getProgramId());
        OrderCreateDto orderCreateDto = new OrderCreateDto();

        // 订单号由用户 ID 参与生成，保证全局唯一并便于分布式场景使用。
        orderCreateDto.setOrderNumber(orderNumber);
        orderCreateDto.setProgramId(programOrderCreateDto.getProgramId());
        orderCreateDto.setProgramItemPicture(programVo.getItemPicture());
        orderCreateDto.setUserId(programOrderCreateDto.getUserId());
        orderCreateDto.setProgramTitle(programVo.getTitle());
        orderCreateDto.setProgramPlace(programVo.getPlace());
        orderCreateDto.setProgramShowTime(programVo.getShowTime());
        orderCreateDto.setProgramPermitChooseSeat(programVo.getPermitChooseSeat());

        // 订单价格以服务端锁定座位的价格为准，不能信任客户端传入金额。
        BigDecimal databaseOrderPrice =
                purchaseSeatList.stream().map(SeatVo::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        orderCreateDto.setOrderPrice(databaseOrderPrice);
        orderCreateDto.setCreateOrderTime(DateUtils.now());
        orderCreateDto.setOrderVersion(orderVersion);

        // 一个座位对应一个实名购票人，按两个列表的相同下标建立绑定关系。
        List<Long> ticketUserIdList = programOrderCreateDto.getTicketUserIdList();
        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = new ArrayList<>();
        for (int i = 0; i < ticketUserIdList.size(); i++) {
            Long ticketUserId = ticketUserIdList.get(i);
            OrderTicketUserCreateDto orderTicketUserCreateDto = new OrderTicketUserCreateDto();
            orderTicketUserCreateDto.setOrderNumber(orderCreateDto.getOrderNumber());
            orderTicketUserCreateDto.setProgramId(programOrderCreateDto.getProgramId());
            orderTicketUserCreateDto.setUserId(programOrderCreateDto.getUserId());
            orderTicketUserCreateDto.setTicketUserId(ticketUserId);

            // 读取与当前购票人下标对应的座位；座位对象为空时按座位不存在处理。
            SeatVo seatVo =
                    Optional.ofNullable(purchaseSeatList.get(i))
                            .orElseThrow(() -> new StellarisFrameException(BaseCode.SEAT_NOT_EXIST));
            orderTicketUserCreateDto.setSeatId(seatVo.getId());
            orderTicketUserCreateDto.setSeatInfo(seatVo.getRowCode()+"排"+seatVo.getColCode()+"列");
            orderTicketUserCreateDto.setTicketCategoryId(seatVo.getTicketCategoryId());
            orderTicketUserCreateDto.setOrderPrice(seatVo.getPrice());
            orderTicketUserCreateDto.setCreateOrderTime(DateUtils.now());
            orderTicketUserCreateDtoList.add(orderTicketUserCreateDto);
        }

        orderCreateDto.setOrderTicketUserCreateDtoList(orderTicketUserCreateDtoList);
        return orderCreateDto;
    }

    /** v5 在执行 Redis Lua 前组装完整订单消息，随后由同一 Lua 写入 Redis Stream。 */
    public OrderCreateMq buildReferenceOrderMessage(ProgramOrderCreateDto request, List<SeatVo> seats,
                                                     Long orderNumber) {
        if (seats == null || request.getTicketUserIdList() == null
                || seats.size() != request.getTicketUserIdList().size()) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_COUNT_UNEQUAL_SEAT_COUNT);
        }
        OrderCreateDto orderCreateDto = buildCreateOrderParam(request, seats,
                com.stellaris.enums.ProgramOrderVersion.V5_REFERENCE.getValue(), orderNumber);
        OrderCreateMq message = new OrderCreateMq();
        BeanUtils.copyProperties(orderCreateDto, message);
        return message;
    }

    /**
     * 根据 Lua 返回的购买座位组装异步订单参数。
     *
     * <p>{@link PurchaseSeat} 已经携带购票人 ID，因此无需再依赖两个列表的下标进行关联。</p>
     *
     * @param programId        节目 ID
     * @param userId           下单用户 ID
     * @param purchaseSeatList Lua 脚本锁定的座位及购票人信息
     * @param orderVersion     订单版本
     * @return 完整的订单创建参数
     */
    private OrderCreateDto buildCreateOrderParamV2(Long programId,Long userId,List<PurchaseSeat> purchaseSeatList,Integer orderVersion){
        // 查询节目快照信息，避免订单展示依赖后续可能发生变化的节目数据。
        ProgramVo programVo = programService.simpleGetProgramAndShowMultipleCache(programId);
        OrderCreateDto orderCreateDto = new OrderCreateDto();
        orderCreateDto.setOrderNumber(uidGenerator.getOrderNumber(userId));
        orderCreateDto.setProgramId(programId);
        orderCreateDto.setProgramItemPicture(programVo.getItemPicture());
        orderCreateDto.setUserId(userId);
        orderCreateDto.setProgramTitle(programVo.getTitle());
        orderCreateDto.setProgramPlace(programVo.getPlace());
        orderCreateDto.setProgramShowTime(programVo.getShowTime());
        orderCreateDto.setProgramPermitChooseSeat(programVo.getPermitChooseSeat());

        // 使用服务端脚本返回的座位价格计算订单总价。
        BigDecimal databaseOrderPrice =
                purchaseSeatList.stream().map(PurchaseSeat::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        orderCreateDto.setOrderPrice(databaseOrderPrice);
        orderCreateDto.setCreateOrderTime(DateUtils.now());
        orderCreateDto.setOrderVersion(orderVersion);

        // 每个 PurchaseSeat 自带 ticketUserId，可直接生成一条订单票人明细。
        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = new ArrayList<>();
        for (PurchaseSeat purchaseSeat : purchaseSeatList) {
            OrderTicketUserCreateDto orderTicketUserCreateDto = new OrderTicketUserCreateDto();
            orderTicketUserCreateDto.setOrderNumber(orderCreateDto.getOrderNumber());
            orderTicketUserCreateDto.setProgramId(programId);
            orderTicketUserCreateDto.setUserId(userId);
            orderTicketUserCreateDto.setTicketUserId(purchaseSeat.getTicketUserId());
            orderTicketUserCreateDto.setSeatId(purchaseSeat.getId());
            orderTicketUserCreateDto.setSeatInfo(purchaseSeat.getRowCode()+"排"+purchaseSeat.getColCode()+"列");
            orderTicketUserCreateDto.setTicketCategoryId(purchaseSeat.getTicketCategoryId());
            orderTicketUserCreateDto.setOrderPrice(purchaseSeat.getPrice());
            orderTicketUserCreateDto.setCreateOrderTime(DateUtils.now());
            orderTicketUserCreateDtoList.add(orderTicketUserCreateDto);
        }
        orderCreateDto.setOrderTicketUserCreateDtoList(orderTicketUserCreateDtoList);
        return orderCreateDto;
    }
    
    /**
     * 通过 RPC 同步调用订单服务创建订单。
     *
     * <p>调用订单服务前，座位已经在节目缓存中被锁定。如果远程创建失败，
     * 必须立即按取消状态回滚余票和座位，避免库存永久占用。</p>
     *
     * @param orderCreateDto  订单创建参数
     * @param purchaseSeatList 已锁定的座位，用于失败时执行缓存回滚
     * @return 订单服务返回的订单编号
     */
    private String createOrderByRpc(OrderCreateDto orderCreateDto,List<SeatVo> purchaseSeatList){
        ApiResponse<String> createOrderResponse = orderClient.create(orderCreateDto);

        if (!Objects.equals(createOrderResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            // 记录完整订单参数，便于对跨服务创建失败进行人工排查和补偿。
            log.error("创建订单失败 需人工处理 orderCreateDto : {}",JSON.toJSONString(orderCreateDto));

            // 订单未创建成功：恢复票档余量，并把座位从锁定区迁回未售出区。
            updateProgramCacheDataResolution(orderCreateDto.getProgramId(),purchaseSeatList,OrderStatus.CANCEL);
            throw new StellarisFrameException(createOrderResponse);
        }
        return createOrderResponse.getData();
    }

    /**
     * 历史异步版本直接发送 Kafka。最终 v5 不调用本方法，而使用锁座 Lua 内的 Redis Stream 事件。
     *
     * @param orderCreateMq   创建订单消息
     * @param purchaseSeatList Lua 脚本已锁定的座位，用于发送失败时回滚
     * @return 消息中的订单编号
     */
    private String createOrderByMq(OrderCreateMq orderCreateMq,List<PurchaseSeat> purchaseSeatList){
        orderCreateMq.setEventId(uidGenerator.getUid());
        try {
            createOrderSend.sendMessage(String.valueOf(orderCreateMq.getOrderNumber()), JSON.toJSONString(orderCreateMq))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            List<SeatVo> purchaseSeatVoList = purchaseSeatList.stream().map(purchaseSeat -> {
                SeatVo seatVo = new SeatVo();
                BeanUtils.copyProperties(purchaseSeat,seatVo);
                return seatVo;
            }).collect(Collectors.toList());
            updateProgramCacheDataResolution(orderCreateMq.getProgramId(), purchaseSeatVoList, OrderStatus.CANCEL);
            throw new StellarisFrameException(ex);
        }

        // 对账任务同步创建；失败不影响已经持久化的订单事件，但会留下明确日志供监控处置。
        try {
            createProgramRecordTask(orderCreateMq.getProgramId());
        } catch (Exception ex) {
            log.error("创建节目库存对账任务失败 eventId:{} orderNumber:{} programId:{}",
                    orderCreateMq.getEventId(), orderCreateMq.getOrderNumber(), orderCreateMq.getProgramId(), ex);
        }

        return String.valueOf(orderCreateMq.getOrderNumber());
    }

    /**
     * 根据订单状态原子更新节目余票和座位缓存。
     *
     * <p>仅支持两种方向：</p>
     * <ul>
     *     <li>{@link OrderStatus#NO_PAY}：扣减余票，座位由“未售出”迁移到“锁定”；</li>
     *     <li>{@link OrderStatus#CANCEL}：返还余票，座位由“锁定”迁移回“未售出”。</li>
     * </ul>
     *
     * @param programId  节目 ID
     * @param seatVoList 本次需要迁移状态的座位
     * @param orderStatus 目标订单状态，只允许 NO_PAY 或 CANCEL
     * @throws StellarisFrameException 传入其他订单状态时抛出
     */
    private void updateProgramCacheDataResolution(Long programId,List<SeatVo> seatVoList,OrderStatus orderStatus){
        // 该缓存操作只定义了“锁座”和“释放座位”两个方向，拒绝其他状态误用。
        if (!(Objects.equals(orderStatus.getCode(), OrderStatus.NO_PAY.getCode()) ||
                Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode()))) {
            throw new StellarisFrameException(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT);
        }

        // Lua 操作器要求至少传入一个 KEYS 参数；此处“#”为占位值。
        List<String> keys = new ArrayList<>();
        keys.add("#");

        /*
         * data[0]：各票档余量的增减指令；
         * data[1]：需要从原座位 Hash 删除的座位 ID；
         * data[2]：需要写入目标座位 Hash 的座位数据。
         */
        String[] data = new String[3];

        // 按票档统计座位数量，因为 Redis 余票是按票档维护的。
        Map<Long, Long> ticketCategoryCountMap =
                seatVoList.stream().collect(Collectors.groupingBy(SeatVo::getTicketCategoryId, Collectors.counting()));

        // 组装每个票档对应的余票增减指令。
        JSONArray jsonArray = new JSONArray();
        ticketCategoryCountMap.forEach((k,v) -> {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("programTicketRemainNumberHashKey",RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, k).getRelKey());
            jsonObject.put("ticketCategoryId",String.valueOf(k));
            if (Objects.equals(orderStatus.getCode(), OrderStatus.NO_PAY.getCode())) {
                // 创建未支付订单时扣减余票，因此使用负数。
                jsonObject.put("count","-" + v);
            } else if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                // 取消订单时返还余票，因此使用正数。
                jsonObject.put("count",v);
            }
            jsonArray.add(jsonObject);
        });

        // 座位缓存同样按票档拆分 Hash，因此先按票档分组。
        Map<Long, List<SeatVo>> seatVoMap = 
                seatVoList.stream().collect(Collectors.groupingBy(SeatVo::getTicketCategoryId));

        // 分别保存“从哪个 Hash 删除哪些座位”和“向哪个 Hash 添加哪些座位”。
        JSONArray delSeatIdjsonArray = new JSONArray();
        JSONArray addSeatDatajsonArray = new JSONArray();
        seatVoMap.forEach((k,v) -> {
            JSONObject delSeatIdjsonObject = new JSONObject();
            JSONObject seatDatajsonObject = new JSONObject();
            String seatHashKeyDel = "";
            String seatHashKeyAdd = "";
            if (Objects.equals(orderStatus.getCode(), OrderStatus.NO_PAY.getCode())) {
                // 锁座：从未售出 Hash 删除，写入锁定 Hash，并同步修改对象中的售卖状态。
                seatHashKeyDel = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, k).getRelKey());
                seatHashKeyAdd = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, k).getRelKey());
                for (SeatVo seatVo : v) {
                    seatVo.setSellStatus(SellStatus.LOCK.getCode());
                }
            } else if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                // 释放：从锁定 Hash 删除，写回未售出 Hash，并恢复对象中的售卖状态。
                seatHashKeyDel = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, k).getRelKey());
                seatHashKeyAdd = (RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, k).getRelKey());
                for (SeatVo seatVo : v) {
                    seatVo.setSellStatus(SellStatus.NO_SOLD.getCode());
                }
            }

            // 构造从源 Hash 删除座位的指令：Redis key + 座位 ID 列表。
            delSeatIdjsonObject.put("seatHashKeyDel",seatHashKeyDel);
            delSeatIdjsonObject.put("seatIdList",v.stream().map(SeatVo::getId).map(String::valueOf).collect(Collectors.toList()));
            delSeatIdjsonArray.add(delSeatIdjsonObject);

            // 构造写入目标 Hash 的指令。
            seatDatajsonObject.put("seatHashKeyAdd",seatHashKeyAdd);

            /*
             * Redis Hash 批量写入参数按 field、value 交替排列：
             * [seatId1, seatJson1, seatId2, seatJson2, ...]。
             */
            List<String> seatDataList = new ArrayList<>();
            for (SeatVo seatVo : v) {
                seatDataList.add(String.valueOf(seatVo.getId()));
                seatDataList.add(JSON.toJSONString(seatVo));
            }
            seatDatajsonObject.put("seatDataList",seatDataList);
            addSeatDatajsonArray.add(seatDatajsonObject);
        });

        // 序列化三类操作指令，并交给 Lua 脚本一次性原子执行。
        data[0] = JSON.toJSONString(jsonArray);
        data[1] = JSON.toJSONString(delSeatIdjsonArray);
        data[2] = JSON.toJSONString(addSeatDatajsonArray);
        programCacheResolutionOperate.programCacheOperate(keys,data);
    }
}
