package com.clean.selfiestick;

import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** adb over a plain TCP socket to this device's own adbd on 127.0.0.1. No VPN involved. */
final class SocketTransport implements AdbTransport {
    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;

    /** Throws IOException starting with "refused" when nothing listens yet (boot retry keys on that). */
    SocketTransport(int port) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        } catch (ConnectException e) {
            s.close();
            throw new IOException("refused: nothing listens on 127.0.0.1:" + port);
        } catch (SocketTimeoutException e) {
            s.close();
            throw new IOException("no answer from 127.0.0.1:" + port);
        }
        s.setTcpNoDelay(true);
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        out.flush();
    }

    @Override
    public void readFully(byte[] b, int off, int len, long timeoutMs) throws IOException {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        int got = 0;
        while (got < len) {
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0) throw new IOException("read timeout");
            sock.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            int r;
            try {
                r = in.read(b, off + got, len - got);
            } catch (SocketTimeoutException e) {
                throw new IOException("read timeout");
            }
            if (r < 0) throw new IOException("EOF from adbd");
            got += r;
        }
    }

    @Override
    public void close() {
        try { sock.close(); } catch (IOException ignored) {}
    }
}
