package com.stellaris.rejectedexecutionhandler;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 拒绝策略
 * @author: xz_y
 **/
public class ThreadPoolRejectedExecutionHandler {
    
    
    public static class BusinessAbortPolicy implements RejectedExecutionHandler {

        public BusinessAbortPolicy() {
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {

            throw new RejectedExecutionException("threadPoolApplicationName business task " + r.toString() +
                    " rejected from " +
                    executor.toString());
        }
    }
}
