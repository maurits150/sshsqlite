package org.sshsqlite.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class FrameCodec {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final int maxFrameBytes;

    FrameCodec(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    JsonNode readJson(InputStream input) throws IOException {
        byte[] payload = readFrame(input);
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("Malformed JSON frame", e);
        }
    }

    void writeJson(OutputStream output, Object value) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(value);
        writeFrame(output, payload);
    }

    byte[] readFrame(InputStream input) throws IOException {
        byte[] header = input.readNBytes(4);
        if (header.length == 0) {
            throw new EOFException("EOF before frame header");
        }
        if (header.length < 4) {
            throw new ProtocolException("Truncated frame header");
        }
        int length = ((header[0] & 0xff) << 24)
                | ((header[1] & 0xff) << 16)
                | ((header[2] & 0xff) << 8)
                | (header[3] & 0xff);
        if (length <= 0) {
            throw new ProtocolException("Invalid frame length: " + length);
        }
        if (length > maxFrameBytes) {
            throw new ProtocolException("Frame exceeds maxFrameBytes");
        }
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new ProtocolException("Truncated frame payload");
        }
        return payload;
    }

    void writeFrame(OutputStream output, byte[] payload) throws IOException {
        if (payload.length == 0) {
            throw new ProtocolException("Empty frames are not allowed");
        }
        if (payload.length > maxFrameBytes) {
            throw new ProtocolException("Frame exceeds maxFrameBytes");
        }
        output.write((payload.length >>> 24) & 0xff);
        output.write((payload.length >>> 16) & 0xff);
        output.write((payload.length >>> 8) & 0xff);
        output.write(payload.length & 0xff);
        output.write(payload);
        output.flush();
    }

    static Map<String, Object> request(long id, String op) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", id);
        request.put("op", op);
        return request;
    }
}
