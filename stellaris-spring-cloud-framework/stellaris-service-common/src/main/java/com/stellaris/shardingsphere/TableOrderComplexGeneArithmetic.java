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
 * @description: 订单分表算法（基因法方案1）
 * 
 * 核心设计：
 * - 订单号生成时固定嵌入userId后6位作为基因
 * - 分表时取低N位作为表索引（N = log2(表数量)）
 * - 配置驱动：扩容时只需修改yaml配置，无需改代码
 * 
 * 基因位分布（userId后6位）：
 * - 低位：表基因（log2(表数量)位）
 * - 中位：库基因（log2(库数量)位）
 * 
 * 示例（2库4表 → 8库8表扩容）：
 * - 当前：取bit0-1作为表索引（低2位）
 * - 扩容后：取bit0-2作为表索引（低3位）
 * 
 * @author: 阿星不是程序员
 **/
public class TableOrderComplexGeneArithmetic implements ComplexKeysShardingAlgorithm<Long> {
    
    /**
     * 属性分表名
     * */
    private static final String SHARDING_COUNT_KEY_NAME = "sharding-count";
    /**
     * 分表数量
     * */
    private int shardingCount;
    
    @Override
    public void init(Properties props) {
        shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY_NAME));
        if (shardingCount <= 0 || (shardingCount & (shardingCount - 1)) != 0) {
            throw new IllegalArgumentException(SHARDING_COUNT_KEY_NAME + " must be a positive power of two");
        }
        if (shardingCount > 64) {
            throw new IllegalArgumentException("table sharding count exceeds the 6-bit route gene capacity");
        }
    }
    
    /**
     * 分表路由逻辑
     * 
     * 核心思路：
     * - 取分片键（订单号或userId）的低N位作为表索引
     * - N = log2(表数量)，通过位运算 (shardingCount - 1) & value 实现
     * - 订单号和userId的低6位相同，所以两种查询都能路由到同一张表
     * 
     * 基因位分布示例（userId后6位 = [bit5][bit4][bit3][bit2][bit1][bit0]）：
     * - 4表：取bit0-1（2位）
     * - 8表：取bit0-2（3位）
     * - 16表：取bit0-3（4位）
     */
    @Override
    public Collection<String> doSharding(Collection<String> allActualSplitTableNames, ComplexKeysShardingValue<Long> complexKeysShardingValue) {
        //返回的真实表名集合
        Set<String> actualTableNames = new LinkedHashSet<>();
        //逻辑表名
        String logicTableName = complexKeysShardingValue.getLogicTableName();
        //查询中的列名和值
        Map<String, Collection<Long>> columnNameAndShardingValuesMap = complexKeysShardingValue.getColumnNameAndShardingValuesMap();
        //如果没有条件查询，那么就查所有的分表
        if (CollectionUtil.isEmpty(columnNameAndShardingValuesMap)) {
            return allActualSplitTableNames;
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
                String actualName = logicTableName + "_" + ((shardingCount - 1) & value);
                if (!allActualSplitTableNames.contains(actualName)) {
                    throw new IllegalStateException("Calculated table is not present in actual data nodes: " + actualName);
                }
                actualTableNames.add(actualName);
            }
            if (actualTableNames.isEmpty()) {
                throw new IllegalStateException("No non-null sharding value was provided");
            }
            return actualTableNames;
        }
        //如果没有分片键查询，则把所有真实表返回
        return allActualSplitTableNames;
    }
}
