package com.stellaris.service;

import com.baidu.fsg.uid.utils.PaddedAtomicLong;
import com.stellaris.dto.TestSendDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: Test service
 * @author: xz_y
 **/
@Slf4j
@Service
public class TestService {
    
    AtomicLong count = new PaddedAtomicLong(0);
    
    public Boolean reset(final TestSendDto testSendDto) {
        count.set(0);
        return true;
    }
}
