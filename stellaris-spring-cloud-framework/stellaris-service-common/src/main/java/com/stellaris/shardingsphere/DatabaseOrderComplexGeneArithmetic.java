package com.stellaris.shardingsphere;

import cn.hutool.core.collection.CollectionUtil;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单分库算法（基因法方案1）
 * 
 * 核心设计：
 * - 订单号生成时固定嵌入userId后6位作为基因
 * - 分库时跳过表基因位，取中间的库基因位
 * - 配置驱动：扩容时只需修改yaml配置，无需改代码
 * 
 * 基因位分布（userId后6位）：
 * - 低位：表基因（log2(表数量)位）
 * - 中位：库基因（log2(库数量)位）
 * 
 * 示例（2库4表 → 8库8表扩容）：
 * - 当前：取bit2作为库索引（右移2位后取低1位）
 * - 扩容后：取bit3-5作为库索引（右移3位后取低3位）
 * 
 * @author: xz_y
 **/
public class DatabaseOrderComplexGeneArithmetic implements ComplexKeysShardingAlgorithm<Long> {
    /**
     * 属性分库名
     * */
    private static final String SHARDING_COUNT_KEY_NAME = "sharding-count";
    
    /**
     * 属性分表名
     * */
    private static final String TABLE_SHARDING_COUNT_KEY_NAME = "table-sharding-count";
    
    /**
     * 分库数量
     * */
    private int shardingCount;
    
    /**
     * 分表数量
     * */
    private int tableShardingCount;
    
    @Override
    public void init(Properties props) {
        this.shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY_NAME));
        this.tableShardingCount = Integer.parseInt(props.getProperty(TABLE_SHARDING_COUNT_KEY_NAME));
        validateShardingCount(shardingCount, SHARDING_COUNT_KEY_NAME);
        validateShardingCount(tableShardingCount, TABLE_SHARDING_COUNT_KEY_NAME);
        if ((long) shardingCount * tableShardingCount > 64L) {
            throw new IllegalArgumentException("database/table sharding count exceeds the 6-bit route gene capacity");
        }
    }
    
   
    @Override
    public Collection<String> doSharding(Collection<String> allActualSplitDatabaseNames, 
                                         ComplexKeysShardingValue<Long> complexKeysShardingValue) {
        //返回的真实库名集合
        Set<String> actualDatabaseNames = new LinkedHashSet<>();
        //查询中的列名和值
        Map<String, Collection<Long>> columnNameAndShardingValuesMap = 
                complexKeysShardingValue.getColumnNameAndShardingValuesMap();
        //如果没有条件查询，那么就查所有的分表
        if (CollectionUtil.isEmpty(columnNameAndShardingValuesMap)) {
            return allActualSplitDatabaseNames;
        }
        //order_number条件的值
        Collection<Long> orderNumberValues = columnNameAndShardingValuesMap.get("order_number");
        //user_id条件的值
        Collection<Long> userIdValues = columnNameAndShardingValuesMap.get("user_id");
        
        Collection<Long> values = CollectionUtil.isNotEmpty(orderNumberValues)
                ? orderNumberValues : userIdValues;
        if (CollectionUtil.isNotEmpty(values)) {
            for (Long value : values) {
                if (value == null) {
                    continue;
                }
                long databaseIndex = calculateDatabaseIndex(shardingCount, value, tableShardingCount);
                String suffix = "_" + databaseIndex;
                allActualSplitDatabaseNames.stream()
                        .filter(name -> name.endsWith(suffix))
                        .findFirst()
                        .ifPresent(actualDatabaseNames::add);
            }
            if (actualDatabaseNames.isEmpty()) {
                throw new IllegalStateException("No actual database matches calculated sharding indices");
            }
            return actualDatabaseNames;
        }
        return allActualSplitDatabaseNames;
    }
    
    /**
     * 计算分库索引
     * 
     * 核心思路：
     * - 分表使用ID的低位bit（最后 log2(tableCount) 位）
     * - 分库使用ID的中高位bit（跳过表基因位后的 log2(databaseCount) 位）
     * - 直接使用位运算，避免hashCode导致的分布不均
     * 
     * 基因位分布示例（userId后6位 = [bit5][bit4][bit3][bit2][bit1][bit0]）：
     * - 2库4表：表基因=bit0-1，库基因=bit2
     * - 4库8表：表基因=bit0-2，库基因=bit3-4
     * - 8库8表：表基因=bit0-2，库基因=bit3-5（用满6位上限）
     *
     * @param databaseCount 数据库总数
     * @param splicingKey   分片键（订单号或userId，低6位包含相同基因）
     * @param tableCount    表总数
     * @return 分配到的数据库编号
     */
    public long calculateDatabaseIndex(Integer databaseCount, Long splicingKey, Integer tableCount) {
        // 计算表分片占用的bit位数
        int tableGeneLength = Integer.numberOfTrailingZeros(tableCount);
        
        // 将分片键右移tableGeneLength位，跳过表基因位
        // 然后与(databaseCount-1)进行按位与运算，得到库索引
        // 例如：ID=1101 (13), tableCount=4, databaseCount=4
        //   tableGeneLength=2, ID>>2=11 (3), (4-1)&3=3
        return (databaseCount - 1) & (splicingKey >> tableGeneLength);
    }
    
    private void validateShardingCount(int count, String propertyName) {
        if (count <= 0 || (count & (count - 1)) != 0) {
            throw new IllegalArgumentException(propertyName + " must be a positive power of two");
        }
    }
}
