package com.stellaris.service;

import com.stellaris.dto.ProgramDataPreheatDto;
import com.stellaris.handler.BloomFilterHandler;
import com.stellaris.service.reference.ReferenceSeatInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgramServiceDataPreheatTest {

    @Test
    void registersDynamicallyPreheatedProgramInBloomFilterWhenInventoryIsReady() {
        long programId = 900000L;
        ReferenceSeatInventoryService inventoryService = mock(ReferenceSeatInventoryService.class);
        BloomFilterHandler bloomFilterHandler = mock(BloomFilterHandler.class);
        when(inventoryService.isReady(programId)).thenReturn(true);

        ProgramService programService = new ProgramService();
        ReflectionTestUtils.setField(programService, "referenceSeatInventoryService", inventoryService);
        ReflectionTestUtils.setField(programService, "bloomFilterHandler", bloomFilterHandler);

        ProgramDataPreheatDto dto = new ProgramDataPreheatDto();
        dto.setProgramId(programId);

        assertTrue(programService.dataPreheat(dto));
        verify(bloomFilterHandler).add(String.valueOf(programId));
    }
}
