package com.stellaris.namefactory;


/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 线程工厂
 * @author: xz_y
 **/
public class BusinessNameThreadFactory extends AbstractNameThreadFactory {

    /**
     * 将线程池工厂的前缀
     * 例子:task-pool--1(线程池的数量)
     */
    @Override
    public String getNamePrefix() {
        return "task-pool" + "--" + POOL_NUM.getAndIncrement();
    }
}
