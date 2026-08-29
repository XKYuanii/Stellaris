package com.stellaris.shardingsphere;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderComplexGeneArithmeticTest {

    @Test
    void databaseShardingMustRouteEveryValueInAnInCondition() {
        DatabaseOrderComplexGeneArithmetic algorithm = new DatabaseOrderComplexGeneArithmetic();
        algorithm.init(properties("sharding-count", "2", "table-sharding-count", "4"));
        ComplexKeysShardingValue<Long> values = values("d_order", "user_id", List.of(0L, 4L));

        Collection<String> actual = algorithm.doSharding(List.of("ds_0", "ds_1"), values);

        assertEquals(List.of("ds_0", "ds_1"), List.copyOf(actual));
    }

    @Test
    void tableShardingMustRouteEveryValueInAnInCondition() {
        TableOrderComplexGeneArithmetic algorithm = new TableOrderComplexGeneArithmetic();
        algorithm.init(properties("sharding-count", "4"));
        ComplexKeysShardingValue<Long> values = values("d_order", "order_number", List.of(64L, 67L));

        Collection<String> actual = algorithm.doSharding(
                List.of("d_order_0", "d_order_1", "d_order_2", "d_order_3"), values);

        assertEquals(List.of("d_order_0", "d_order_3"), List.copyOf(actual));
    }

    @Test
    void invalidOrOversizedTopologyMustFailAtStartup() {
        DatabaseOrderComplexGeneArithmetic database = new DatabaseOrderComplexGeneArithmetic();
        TableOrderComplexGeneArithmetic table = new TableOrderComplexGeneArithmetic();

        assertThrows(IllegalArgumentException.class,
                () -> database.init(properties("sharding-count", "3", "table-sharding-count", "4")));
        assertThrows(IllegalArgumentException.class,
                () -> database.init(properties("sharding-count", "8", "table-sharding-count", "16")));
        assertThrows(IllegalArgumentException.class,
                () -> table.init(properties("sharding-count", "128")));
    }

    private ComplexKeysShardingValue<Long> values(String logicTable, String column, Collection<Long> values) {
        return new ComplexKeysShardingValue<>(logicTable, Map.of(column, values), Map.of());
    }

    private Properties properties(String... values) {
        Properties properties = new Properties();
        for (int i = 0; i < values.length; i += 2) {
            properties.setProperty(values[i], values[i + 1]);
        }
        return properties;
    }
}
