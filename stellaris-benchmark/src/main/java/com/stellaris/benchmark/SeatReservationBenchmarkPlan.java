package com.stellaris.benchmark;

import java.util.List;

/**
 * 锁座压测的固定实验矩阵。压测执行器可读取此定义，报告必须包含所有字段。
 * 本模块不进入任一线上服务的运行时依赖。
 */
public final class SeatReservationBenchmarkPlan {
    private SeatReservationBenchmarkPlan() {
    }

    public static List<Implementation> implementations() {
        return List.of(Implementation.PROGRAM_LOCK, Implementation.SEAT_LOCK,
                Implementation.FULL_SCAN_LUA, Implementation.ZSET_CAS_LUA);
    }

    public static List<Scenario> scenarios() {
        return List.of(
                new Scenario(1, Conflict.LOW, 1_000, Mode.MANUAL, Topology.SINGLE, 50),
                new Scenario(2, Conflict.MEDIUM, 10_000, Mode.AUTO, Topology.SINGLE, 200),
                new Scenario(4, Conflict.HOT, 50_000, Mode.AUTO, Topology.CLUSTER_3_PRIMARY, 500));
    }

    public enum Implementation {
        PROGRAM_LOCK, SEAT_LOCK, FULL_SCAN_LUA, ZSET_CAS_LUA
    }

    public enum Conflict {
        LOW, MEDIUM, HOT
    }

    public enum Mode {
        MANUAL, AUTO
    }

    public enum Topology {
        SINGLE, CLUSTER_3_PRIMARY
    }

    public record Scenario(int ticketCount, Conflict conflict, int seatCount, Mode mode,
                           Topology topology, int concurrency) {
    }
}
