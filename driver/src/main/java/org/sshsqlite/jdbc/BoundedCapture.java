package org.sshsqlite.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class BoundedCapture implements Runnable {
    private final InputStream input;
    private final byte[] buffer;
    private int start;
    private int size;
    private long totalBytes;

    BoundedCapture(InputStream input, int maxBytes) {
        this.input = input;
        this.buffer = new byte[maxBytes];
    }

    @Override
    public void run() {
        byte[] chunk = new byte[1024];
        try {
            int read;
            while ((read = input.read(chunk)) != -1) {
                append(chunk, read);
            }
        } catch (IOException ignored) {
            // Diagnostics are best-effort and must not block protocol progress.
        }
    }

    synchronized long position() {
        return totalBytes;
    }

    synchronized String text() {
        return textFromBuffer(0, size);
    }

    synchronized String textSince(long position) {
        long firstBuffered = totalBytes - size;
        int offset = (int) Math.min(size, Math.max(0, position - firstBuffered));
        return textFromBuffer(offset, size - offset);
    }

    private String textFromBuffer(int offset, int length) {
        byte[] out = new byte[size];
        for (int i = 0; i < length; i++) {
            out[i] = buffer[(start + offset + i) % buffer.length];
        }
        String text = new String(out, 0, length, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
        return Redactor.sanitize(text);
    }

    private synchronized void append(byte[] source, int length) {
        for (int i = 0; i < length; i++) {
            if (size < buffer.length) {
                buffer[(start + size) % buffer.length] = source[i];
                size++;
            } else {
                buffer[start] = source[i];
                start = (start + 1) % buffer.length;
            }
            totalBytes++;
        }
    }
}
