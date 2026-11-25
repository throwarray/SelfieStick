package com.clean.selfiestick;

import java.io.IOException;

/** The byte pipe the adb client talks through: a VPN tun (MiniTcp) or a plain socket to localhost (SocketTransport). */
interface AdbTransport {
    void write(byte[] b, int off, int len) throws IOException;

    void readFully(byte[] b, int off, int len, long timeoutMs) throws IOException;

    void close();
}
