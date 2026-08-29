package com.stellaris.toolkit;

import cn.hutool.core.date.SystemClock;
import cn.hutool.core.lang.Assert;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 雪花算法
 * @author: 阿星不是程序员
 **/
@Slf4j
public class SnowflakeIdGenerator {
    
    private static final long BASIS_TIME = 1288834974657L;
    /**
     * 订单号使用独立布局。2024-01-01 作为纪元，40 位毫秒时间可使用约 34 年。
     *
     * <pre>
     * 0 | 40 bit timestamp | 5 bit datacenter | 5 bit worker | 7 bit sequence | 6 bit route gene
     * </pre>
     *
     * 原实现直接在标准雪花 ID 的 12 位序列号后追加 6 位基因，覆盖了 worker/data-center 位，
     * 在同一毫秒内会产生碰撞。订单号因此必须使用完全独立且互不重叠的位段。
     */
    private static final long ORDER_BASIS_TIME = 1704067200000L;
    private static final long ORDER_GENE_BITS = 6L;
    private static final long ORDER_SEQUENCE_BITS = 7L;
    private static final long ORDER_WORKER_ID_SHIFT = ORDER_GENE_BITS + ORDER_SEQUENCE_BITS;
    private static final long ORDER_DATACENTER_ID_SHIFT = ORDER_WORKER_ID_SHIFT + 5L;
    private static final long ORDER_TIMESTAMP_SHIFT = ORDER_DATACENTER_ID_SHIFT + 5L;
    private static final long ORDER_SEQUENCE_MASK = (1L << ORDER_SEQUENCE_BITS) - 1;
    private static final long ORDER_GENE_MASK = (1L << ORDER_GENE_BITS) - 1;
    private static final long ORDER_TIMESTAMP_MASK = (1L << 40L) - 1;
    private final long workerIdBits = 5L;
    private final long datacenterIdBits = 5L;
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits);
    private final long maxDatacenterId = -1L ^ (-1L << datacenterIdBits);
    
    private final long sequenceBits = 12L;
    private final long workerIdShift = sequenceBits;
    private final long datacenterIdShift = sequenceBits + workerIdBits;
    
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);
    
    private final long workerId;
    
    
    private final long datacenterId;

    /** 仅 Redis 分配的生产节点携带租约；显式 worker/datacenter 构造器用于测试与离线工具。 */
    private final WorkDataCenterId nodeIdentity;
   
    private long sequence = 0L;
   
    private long lastTimestamp = -1L;

    private long orderSequence = 0L;

    private long lastOrderTimestamp = -1L;
    
    private InetAddress inetAddress;
    
    public SnowflakeIdGenerator(WorkDataCenterId workDataCenterId) {
        if (Objects.nonNull(workDataCenterId)
                && Objects.nonNull(workDataCenterId.getWorkId())
                && Objects.nonNull(workDataCenterId.getDataCenterId())) {
            Assert.isFalse(workDataCenterId.getWorkId() > maxWorkerId || workDataCenterId.getWorkId() < 0,
                    "worker Id is out of range");
            Assert.isFalse(workDataCenterId.getDataCenterId() > maxDatacenterId
                            || workDataCenterId.getDataCenterId() < 0,
                    "datacenter Id is out of range");
            this.workerId = workDataCenterId.getWorkId();
            this.datacenterId = workDataCenterId.getDataCenterId();
            this.nodeIdentity = workDataCenterId;
        }else {
            this.datacenterId = getDatacenterId(maxDatacenterId);
            workerId = getMaxWorkerId(datacenterId, maxWorkerId);
            this.nodeIdentity = null;
        }
    }

    public SnowflakeIdGenerator(InetAddress inetAddress) {
        this.inetAddress = inetAddress;
        this.datacenterId = getDatacenterId(maxDatacenterId);
        this.workerId = getMaxWorkerId(datacenterId, maxWorkerId);
        this.nodeIdentity = null;
        initLog();
    }

    private void initLog() {
        if (log.isDebugEnabled()) {
            log.debug("Initialization SnowflakeIdGenerator datacenterId:" + this.datacenterId + " workerId:" + this.workerId);
        }
    }
    
    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        Assert.isFalse(workerId > maxWorkerId || workerId < 0,
            String.format("worker Id can't be greater than %d or less than 0", maxWorkerId));
        Assert.isFalse(datacenterId > maxDatacenterId || datacenterId < 0,
            String.format("datacenter Id can't be greater than %d or less than 0", maxDatacenterId));
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.nodeIdentity = null;
        initLog();
    }
    
    protected long getMaxWorkerId(long datacenterId, long maxWorkerId) {
        StringBuilder mpid = new StringBuilder();
        mpid.append(datacenterId);
        String name = ManagementFactory.getRuntimeMXBean().getName();
        if (StringUtils.isNotBlank(name)) {
            mpid.append(name.split("@")[0]);
        }
        return (mpid.toString().hashCode() & 0xffff) % (maxWorkerId + 1);
    }
    
    protected long getDatacenterId(long maxDatacenterId) {
        long id = 0L;
        try {
            if (null == this.inetAddress) {
                this.inetAddress = InetAddress.getLocalHost();
            }
            NetworkInterface network = NetworkInterface.getByInetAddress(this.inetAddress);
            if (null == network) {
                id = 1L;
            } else {
                byte[] mac = network.getHardwareAddress();
                if (null != mac) {
                    id = ((0x000000FF & (long) mac[mac.length - 2]) | (0x0000FF00 & (((long) mac[mac.length - 1]) << 8))) >> 6;
                    id = id % (maxDatacenterId + 1);
                }
            }
        } catch (Exception e) {
            log.warn(" getDatacenterId: " + e.getMessage());
        }
        return id;
    }
    
    public long getBase(){
        int five = 5;
        long timestamp = timeGen();
        //闰秒
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= five) {
                try {
                    wait(offset << 1);
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        throw new RuntimeException(String.format("Clock moved backwards.  Refusing to generate id for %d milliseconds", offset));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException(String.format("Clock moved backwards.  Refusing to generate id for %d milliseconds", offset));
            }
        }
        
        if (lastTimestamp == timestamp) {
            // 相同毫秒内，序列号自增
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                // 同一毫秒的序列数已经达到最大
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 不同毫秒内，序列号置为 1 - 3 随机数
            sequence = ThreadLocalRandom.current().nextLong(1, 3);
        }
        
        lastTimestamp = timestamp;
        
        return timestamp;
    }
    
    public synchronized long nextId() {
        assertNodeLeaseValid();
        long timestamp = getBase();
        
        return ((timestamp - BASIS_TIME) << timestampLeftShift)
            | (datacenterId << datacenterIdShift)
            | (workerId << workerIdShift)
            | sequence;
    }
    
    /**
     * @deprecated 此方法已被废弃，不需要学习
     */
    @Deprecated
    public synchronized long getOrderNumber(long userId, long tableCount, long databaseCount) {
        validateRouteCapacity(tableCount, databaseCount);
        return getOrderNumber(userId);
    }
    
    /**
     * @deprecated 此方法已被废弃，不需要学习
     */
    @Deprecated
    public synchronized long getOrderNumber(long userId, long tableCount) {
        validateRouteCapacity(tableCount, 1L);
        return getOrderNumber(userId);
    }
    
    /**
     * 【方案1】生成订单编号 - 固定预留6位基因位
     * 核心思想：预留足够多的基因位，支持未来扩容而无需修改生成逻辑
     * 
     * 基因位分配（6位可支持64种组合）：
     * - 当前：2库4表 = 8种组合，占用3位
     * - 最大支持：8库 × 8表 = 64种组合
     * - 或：4库 × 16表 = 64种组合
     * 
     * 订单号结构：[时间戳][数据中心ID][机器ID][序列号][userId后6位]
     * 
     * 扩容时只需修改分片算法配置，无需修改此方法
     * 
     * @param userId 用户ID
     * @return 订单编号
     */
    public synchronized long getOrderNumber(long userId) {
        assertNodeLeaseValid();
        long timestamp = getOrderBase();
        long elapsed = timestamp - ORDER_BASIS_TIME;
        if (elapsed < 0 || elapsed > ORDER_TIMESTAMP_MASK) {
            throw new IllegalStateException("Order number timestamp is outside the supported range");
        }
        long userGene = userId & ORDER_GENE_MASK;
        return (elapsed << ORDER_TIMESTAMP_SHIFT)
                | (datacenterId << ORDER_DATACENTER_ID_SHIFT)
                | (workerId << ORDER_WORKER_ID_SHIFT)
                | (orderSequence << ORDER_GENE_BITS)
                | userGene;
    }

    private long getOrderBase() {
        long timestamp = timeGen();
        if (timestamp < lastOrderTimestamp) {
            long offset = lastOrderTimestamp - timestamp;
            throw new IllegalStateException(String.format(
                    "Clock moved backwards. Refusing to generate order number for %d milliseconds", offset));
        }
        if (timestamp == lastOrderTimestamp) {
            orderSequence = (orderSequence + 1) & ORDER_SEQUENCE_MASK;
            if (orderSequence == 0L) {
                timestamp = tilNextMillis(lastOrderTimestamp);
            }
        } else {
            orderSequence = 0L;
        }
        lastOrderTimestamp = timestamp;
        return timestamp;
    }

    private void validateRouteCapacity(long tableCount, long databaseCount) {
        Assert.isTrue(tableCount > 0 && databaseCount > 0, "sharding counts must be positive");
        Assert.isTrue(isPowerOfTwo(tableCount) && isPowerOfTwo(databaseCount),
                "sharding counts must be powers of two");
        Assert.isTrue(tableCount * databaseCount <= (1L << ORDER_GENE_BITS),
                "total sharding count exceeds the 6-bit route gene capacity");
    }

    private boolean isPowerOfTwo(long value) {
        return (value & (value - 1)) == 0;
    }

    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    protected long timeGen() {
        return SystemClock.now();
    }
    
    public static long parseIdTimestamp(long id) {
        return (id>>22)+ BASIS_TIME;
    }

    public static long parseOrderNumberTimestamp(long orderNumber) {
        return (orderNumber >> ORDER_TIMESTAMP_SHIFT) + ORDER_BASIS_TIME;
    }
    
    public long log2N(long count) {
        return (long)(Math.log(count)/ Math.log(2));
    }
    
    public long getMaxWorkerId() {
        return maxWorkerId;
    }
    
    public long getMaxDatacenterId() {
        return maxDatacenterId;
    }

    private void assertNodeLeaseValid() {
        if (nodeIdentity != null) {
            nodeIdentity.assertLeaseValid();
        }
    }
}
