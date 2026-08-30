package com.stellaris.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 延迟队列 分片选择器
 * @author: xz_y
 **/
public class IsolationRegionSelector {

	private final AtomicInteger count = new AtomicInteger(0);

	private final Integer thresholdValue;

	public IsolationRegionSelector(Integer thresholdValue) {
		this.thresholdValue = thresholdValue;
	}

	private int reset() {
		count.set(0);
		return count.get();
	}
	
	public synchronized int getIndex() {
		int cur = count.get();
		if (cur >= thresholdValue) {
			cur = reset();
		} else {
			count.incrementAndGet();
		}
		return cur;
	}
}
