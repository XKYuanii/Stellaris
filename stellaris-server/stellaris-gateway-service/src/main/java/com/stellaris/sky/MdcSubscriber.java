package com.stellaris.sky;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

import java.lang.reflect.Field;
import java.util.Optional;

import static com.stellaris.constant.Constant.SKY_WALKING_TRACE_ID;
import static com.stellaris.constant.Constant.TRACE_ID;

@Slf4j
public class MdcSubscriber implements CoreSubscriber {


    private static final String SKYWALKING_CTX_SNAPSHOT = "SKYWALKING_CONTEXT_SNAPSHOT";

    private final CoreSubscriber<Object> actual;

    public MdcSubscriber(CoreSubscriber<Object> actual) {
        this.actual = actual;
    }

    @Override
    public void onSubscribe(Subscription s) {
        actual.onSubscribe(s);
    }

    @Override
    public void onNext(Object o) {
        Context c = actual.currentContext();
        Optional<String> traceIdOptional = Optional.empty();
        if (!c.isEmpty() && c.hasKey(SKYWALKING_CTX_SNAPSHOT)) {
            Object object = c.get(SKYWALKING_CTX_SNAPSHOT);
            Object traceId = findField(object, TRACE_ID);
            String ids = JSON.toJSONString(traceId);
            traceIdOptional = Optional.ofNullable(ids)
                    .map(JSON::parseObject)
                    .map(t -> t.get("id"))
                    .map(Object::toString);
        }

        MDC.put(SKY_WALKING_TRACE_ID, traceIdOptional.orElse("N/A"));
        actual.onNext(o);
    }

    @Override
    public void onError(Throwable throwable) {
        actual.onError(throwable);
    }

    @Override
    public void onComplete() {
        actual.onComplete();
    }

    @Override
    public Context currentContext() {
        return actual.currentContext();
    }
    
    private static Object findField(Object object, String field) {
        if (object == null) {
            return null;
        }
        try {
            Class<?> aClass = object.getClass();
            
            Field mValuesField = null;
            mValuesField = aClass.getDeclaredField(field);
            // 3、打开访问权限
            mValuesField.setAccessible(true);
            // 4、获取 memberValuesMap
            return mValuesField.get(object);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}