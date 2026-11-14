// SPDX-License-Identifier: GPL-3.0-or-later
// Classic ATT handles and requests derived from LibrePods ATTManager.kt.
package com.tlsu.pods;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class AttChannel implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ColorPodsATTTimeout"); t.setDaemon(true); return t;
    });
    private final BluetoothSocket socket;
    private final LinkedBlockingQueue<byte[]> replies = new LinkedBlockingQueue<>(32);
    private volatile boolean closed;
    private int mtu;

    AttChannel(BluetoothDevice device) throws Exception {
        socket = (BluetoothSocket) Reflect.call(device, "createL2capSocket", new Class[]{int.class}, 31);
        var timeout = TIMER.schedule(this::close, 5, TimeUnit.SECONDS);
        try { socket.connect(); } catch (Exception e) { close(); throw e; }
        finally { timeout.cancel(false); }
        // ATT_EXCHANGE_MTU is LE-only. On BR/EDR, L2CAP has already negotiated
        // the MTU; sending an LE Exchange MTU here makes AirPods stop responding.
        int tx = socket.getMaxTransmitPacketSize(), rx = socket.getMaxReceivePacketSize();
        mtu = tx > 0 && rx > 0 ? Math.min(512, Math.min(tx, rx)) : 48;
        if (mtu < 48) { close(); throw new IOException("无效的 BR/EDR ATT 通道 MTU"); }
        Thread reader = new Thread(this::readLoop, "ColorPodsATTReader"); reader.setDaemon(true); reader.start();
    }
    private void readLoop() {
        byte[] buffer = new byte[1024];
        try {
            while (!closed) {
                int n = socket.getInputStream().read(buffer);
                if (n <= 0) break;
                byte[] pdu = Arrays.copyOf(buffer, n);
                int opcode = pdu[0] & 255;
                if (opcode == 0x1d) { // Indication confirmation; not a transaction response.
                    synchronized (socket) { socket.getOutputStream().write(new byte[]{0x1e}); socket.getOutputStream().flush(); }
                } else if (opcode != 0x1b && !replies.offer(pdu)) {
                    throw new IOException("ATT response queue overflow");
                }
            }
        } catch (IOException ignored) {} finally { close(); }
    }
    private byte[] exchange(byte[] pdu, int responseOpcode) throws Exception {
        if (closed) throw new IOException("ATT 连接已断开，请重新读取");
        synchronized (socket) { socket.getOutputStream().write(pdu); socket.getOutputStream().flush(); }
        byte[] response = replies.poll(2500, TimeUnit.MILLISECONDS);
        if (response == null || response.length == 0) {
            close(); throw new IOException("耳机 ATT 响应超时，请确认 Apple 身份已提交并重新连接耳机");
        }
        if (response[0] == 1) {
            int error = response.length >= 5 ? response[4] & 255 : -1;
            throw new IOException("耳机拒绝 ATT 请求（错误 " + error + "），此型号或固件可能不支持");
        }
        if ((response[0] & 255) != responseOpcode) { close(); throw new IOException("ATT 响应不匹配，已关闭连接"); }
        return response;
    }
    synchronized byte[] read(int handle) throws Exception {
        AttCodec.validateHandle(handle);
        byte[] r = exchange(new byte[]{0x0a, (byte)handle, 0}, 0x0b);
        byte[] value = Arrays.copyOfRange(r, 1, r.length);
        int limit = handle == AttCodec.LOUD_SOUND ? 1 : 104;
        while (r.length == mtu && value.length < limit) {
            int offset = value.length;
            r = exchange(new byte[]{0x0c, (byte)handle, 0, (byte)offset, (byte)(offset >> 8)}, 0x0d);
            if (r.length < 2) break;
            byte[] combined = Arrays.copyOf(value, value.length + r.length - 1);
            System.arraycopy(r, 1, combined, value.length, r.length - 1); value = combined;
            if (value.length > 512) { close(); throw new IOException("ATT 设置长度异常"); }
        }
        AttCodec.validateValue(handle, value);
        return value;
    }
    synchronized void write(int handle, byte[] value) throws Exception {
        AttCodec.validateValue(handle, value);
        if (value.length <= mtu - 3) {
            byte[] request = new byte[value.length + 3]; request[0] = 0x12; request[1] = (byte)handle;
            System.arraycopy(value, 0, request, 3, value.length); exchange(request, 0x13);
        } else {
            try {
                for (int offset = 0; offset < value.length; offset += mtu - 5) {
                    int n = Math.min(mtu - 5, value.length - offset);
                    byte[] pdu = new byte[n + 5]; pdu[0] = 0x16; pdu[1] = (byte)handle;
                    pdu[3] = (byte)offset; pdu[4] = (byte)(offset >> 8);
                    System.arraycopy(value, offset, pdu, 5, n);
                    byte[] response = exchange(pdu, 0x17);
                    byte[] expected = pdu.clone(); expected[0] = 0x17;
                    if (!Arrays.equals(response, expected)) throw new IOException("ATT 长写入校验失败");
                }
                exchange(new byte[]{0x18, 1}, 0x19);
            } catch (Exception e) {
                if (!closed) try { exchange(new byte[]{0x18, 0}, 0x19); } catch (Exception ignored) { close(); }
                throw e;
            }
        }
    }
    @Override public void close() {
        closed = true; try { socket.close(); } catch (IOException ignored) {}
        replies.offer(new byte[0]);
    }
    boolean alive() { return !closed && socket.isConnected(); }
}
