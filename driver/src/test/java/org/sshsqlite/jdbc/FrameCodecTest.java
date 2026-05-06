package org.sshsqlite.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {
    @Test
    void writesAndReadsRepeatedRequests() throws Exception {
        FrameCodec codec = new FrameCodec(1024);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.writeJson(out, Map.of("id", 1, "op", "ping"));
        codec.writeJson(out, Map.of("id", 2, "op", "ping"));

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        JsonNode first = codec.readJson(in);
        JsonNode second = codec.readJson(in);

        assertEquals(1, first.path("id").asInt());
        assertEquals(2, second.path("id").asInt());
        assertThrows(EOFException.class, () -> codec.readJson(in));
    }

    @Test
    void enforcesMaxFrameBeforeAllocation() {
        byte[] oversizedHeader = new byte[]{0, 0, 4, 1};

        assertThrows(ProtocolException.class,
                () -> new FrameCodec(1024).readFrame(new ByteArrayInputStream(oversizedHeader)));
        assertThrows(ProtocolException.class,
                () -> new FrameCodec(4).writeFrame(new ByteArrayOutputStream(), new byte[5]));
    }

    @Test
    void rejectsMalformedAndTruncatedFrames() {
        assertThrows(ProtocolException.class,
                () -> new FrameCodec(1024).readFrame(new ByteArrayInputStream(new byte[]{0, 0, 0, 0})));
        assertThrows(ProtocolException.class,
                () -> new FrameCodec(1024).readFrame(new ByteArrayInputStream(new byte[]{0, 0, 0, 2, '{'})));
    }
}
