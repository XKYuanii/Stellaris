package com.stellaris.handler;

import com.stellaris.enums.MessageType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExceptionMessageHandlerContextTest {

    @SuppressWarnings("unchecked")
    @Test
    void removedMessageHandlerIsReportedAsUnsupported() {
        ObjectProvider<ExceptionMessageHandler> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        ExceptionMessageHandlerContext context = new ExceptionMessageHandlerContext(provider);
        context.init();

        assertThat(context.supports(MessageType.DELAY_ORDER_CANCEL)).isFalse();
    }
}
