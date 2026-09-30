package com.koalaman64.italytravelpocketguide;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Actual-byte bounds and close-before-success, shared by SAF and JVM failure tests. */
final class JsonTransferIo {
    interface Check { void run() throws IOException; }
    static String read(InputStream stream, Check check) throws IOException, JsonTransferJson.Failure {
        String payload;
        try (InputStream in = stream) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                check.run();
                int count = in.read(buffer, 0, Math.min(buffer.length, JsonTransferJson.PAYLOAD_LIMIT + 1 - result.size()));
                if (count == -1) break;
                if (count == 0) throw new IOException("Provider made no progress");
                result.write(buffer, 0, count);
                if (result.size() > JsonTransferJson.PAYLOAD_LIMIT) throw new JsonTransferJson.Failure("FILE_LIMIT");
            }
            payload = JsonTransferJson.decode(result.toByteArray());
            JsonTransferProtocol.payload(payload);
        }
        check.run();
        return payload;
    }
    static void write(OutputStream stream, byte[] payload, Check check) throws IOException {
        try (OutputStream out = stream) {
            for (int offset = 0; offset < payload.length; offset += 8192) {
                check.run(); out.write(payload, offset, Math.min(8192, payload.length - offset));
            }
            check.run(); out.flush();
        }
        check.run();
    }
}
