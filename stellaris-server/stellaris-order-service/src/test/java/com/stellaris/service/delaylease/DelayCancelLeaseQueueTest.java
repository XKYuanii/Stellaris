package com.stellaris.service.delaylease;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelayCancelLeaseQueueTest {

    @Test
    void acceptsAllEmptyQueueRepresentations() {
        assertEquals(List.of(), DelayCancelLeaseQueue.parseClaimResult(null));
        assertEquals(List.of(), DelayCancelLeaseQueue.parseClaimResult("{}"));
        assertEquals(List.of(), DelayCancelLeaseQueue.parseClaimResult("[]"));
    }

    @Test
    void parsesClaimedTaskIds() {
        assertEquals(List.of("order-1", "order-2"),
                DelayCancelLeaseQueue.parseClaimResult("[\"order-1\",\"order-2\"]"));
    }
}
