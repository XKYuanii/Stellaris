package com.stellaris.mybatisplus;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.stellaris.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;
/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: mybatisPlus更新填充
 * @author: xz_y
 **/
@Slf4j
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {
    
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", DateUtils::now, Date.class);
        this.strictInsertFill(metaObject, "editTime", DateUtils::now, Date.class);
    }
    
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "editTime", DateUtils::now, Date.class);
    }
}