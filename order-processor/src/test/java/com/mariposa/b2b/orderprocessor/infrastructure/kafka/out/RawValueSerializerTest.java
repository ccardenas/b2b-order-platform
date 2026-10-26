package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.InboundOrder;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawValueSerializerTest {

    private final RawValueSerializer serializer = new RawValueSerializer();

    @Test
    void writesBytesAsTheyAreAndInboundOrdersAsTheirOriginalBytes() {
        byte[] raw = {1, 2, 3};

        assertThat(serializer.serialize("t", raw)).isSameAs(raw);
        assertThat(serializer.serialize("t", new InboundOrder(null, raw))).isSameAs(raw);
        assertThat(serializer.serialize("t", null)).isNull();
    }

    @Test
    void neverReSerializesAnythingElse() {
        assertThatThrownBy(() -> serializer.serialize("t", "text")).isInstanceOf(SerializationException.class);
    }
}
