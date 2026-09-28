package by.dzianis.budspopup.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Huawei FreeBuds SPP protocol (port of OpenFreebuds, GPL-3.0).
 *
 * Packet: 'Z' | len(2, BE) = body+1 | 0x00 | cmd(2) | [type(1) len(1) value]... | crc16-xmodem(2, BE)
 * The CRC covers everything before it.
 */
public final class HuaweiSpp {

    public static final byte[] CMD_BATTERY_READ = {0x01, 0x08};
    public static final byte[] CMD_BATTERY_NOTIFY = {0x01, 0x27};
    public static final byte[] CMD_ANC_READ = {0x2b, 0x2a};
    public static final byte[] CMD_DEVICE_INFO = {0x01, 0x07};

    private HuaweiSpp() {}

    // ------------------------------------------------------------------ packet

    public static final class Packet {
        public final byte[] command;
        public final Map<Integer, byte[]> params = new LinkedHashMap<>();

        public Packet(byte[] command) {
            if (command.length != 2) throw new IllegalArgumentException("command must be 2 bytes");
            this.command = command.clone();
        }

        public Packet param(int type, byte[] value) {
            params.put(type & 0xff, value);
            return this;
        }

        public byte[] param(int type) {
            byte[] v = params.get(type);
            return v == null ? new byte[0] : v;
        }

        public boolean isCommand(byte[] cmd) {
            return command[0] == cmd[0] && command[1] == cmd[1];
        }

        public byte[] toBytes() {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(command, 0, 2);
            for (Map.Entry<Integer, byte[]> e : params.entrySet()) {
                byte[] v = e.getValue();
                body.write(e.getKey());
                body.write(v.length);
                body.write(v, 0, v.length);
            }
            byte[] b = body.toByteArray();
            int len = b.length + 1;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write('Z');
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
            out.write(0);
            out.write(b, 0, b.length);
            byte[] head = out.toByteArray();
            int crc = crc16Xmodem(head, 0, head.length);
            out.write((crc >> 8) & 0xff);
            out.write(crc & 0xff);
            return out.toByteArray();
        }
    }

    /** Read request: every requested parameter type is sent with an empty value. */
    public static Packet readRequest(byte[] cmd, int... types) {
        Packet p = new Packet(cmd);
        for (int t : types) p.param(t, new byte[0]);
        return p;
    }

    /** Parse a full frame. Returns null for malformed frames or bad CRC. */
    public static Packet parse(byte[] data) {
        if (data == null || data.length < 8 || data[0] != 'Z' || data[3] != 0) return null;
        int length = ((data[1] & 0xff) << 8) | (data[2] & 0xff);
        // length = 0x00 byte + cmd(2) + params. Frame = 'Z' + len(2) + length + crc(2).
        int total = 3 + length + 2;
        if (data.length < total) return null;
        int crc = crc16Xmodem(data, 0, total - 2);
        int got = ((data[total - 2] & 0xff) << 8) | (data[total - 1] & 0xff);
        if (crc != got) return null;

        Packet p = new Packet(new byte[]{data[4], data[5]});
        int pos = 6;
        int end = 3 + length;
        while (pos + 1 < end) {
            int type = data[pos] & 0xff;
            int plen = data[pos + 1] & 0xff;
            if (pos + 2 + plen > end) return null;
            byte[] v = new byte[plen];
            System.arraycopy(data, pos + 2, v, 0, plen);
            p.params.put(type, v);
            pos += 2 + plen;
        }
        return p;
    }

    /**
     * Blocking read of the next frame from an RFCOMM stream.
     * Skips garbage until a 'Z' sync byte. Returns null on EOF.
     */
    public static Packet readFrame(InputStream in) throws IOException {
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            if (b != 'Z') continue;
            byte[] hdr = readFully(in, 2);
            if (hdr == null) return null;
            int length = ((hdr[0] & 0xff) << 8) | (hdr[1] & 0xff);
            if (length < 3 || length > 4096) continue;
            byte[] rest = readFully(in, length + 2);
            if (rest == null) return null;
            byte[] frame = new byte[3 + length + 2];
            frame[0] = 'Z';
            frame[1] = hdr[0];
            frame[2] = hdr[1];
            System.arraycopy(rest, 0, frame, 3, rest.length);
            Packet p = parse(frame);
            if (p != null) return p;
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return buf;
    }

    // ------------------------------------------------------------------ crc

    public static int crc16Xmodem(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xff) << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }

    // ------------------------------------------------------------------ battery

    public static final class Battery {
        /** -1 when unknown. */
        public int global = -1, left = -1, right = -1, caseLevel = -1;
        public boolean leftCharging, rightCharging, caseCharging, anyCharging;

        public boolean hasTws() { return left >= 0 && right >= 0; }

        @Override public String toString() {
            return "Battery{global=" + global + ", L=" + left + ", R=" + right + ", case=" + caseLevel
                    + ", charging L/R/C=" + leftCharging + "/" + rightCharging + "/" + caseCharging + "}";
        }
    }

    public static Packet batteryRequest() {
        return readRequest(CMD_BATTERY_READ, 1, 2, 3);
    }

    /** Works for both the read response (01 08) and the push notification (01 27). */
    public static Battery parseBattery(Packet p) {
        if (p == null || !(p.isCommand(CMD_BATTERY_READ) || p.isCommand(CMD_BATTERY_NOTIFY))) return null;
        Battery b = new Battery();
        byte[] g = p.param(1);
        if (g.length == 1) b.global = g[0] & 0xff;
        byte[] tws = p.param(2);
        if (tws.length == 3) {
            b.left = tws[0] & 0xff;
            b.right = tws[1] & 0xff;
            b.caseLevel = tws[2] & 0xff;
        }
        byte[] ch = p.param(3);
        for (byte c : ch) if (c == 1) b.anyCharging = true;
        if (ch.length == 3) {
            b.leftCharging = ch[0] == 1;
            b.rightCharging = ch[1] == 1;
            b.caseCharging = ch[2] == 1;
        } else if (ch.length == 1) {
            b.caseCharging = ch[0] == 1;
        }
        return b;
    }

    // ------------------------------------------------------------------ anc

    public static final String[] ANC_MODES = {"off", "cancellation", "awareness"};

    public static Packet ancRequest() {
        return readRequest(CMD_ANC_READ, 1, 2);
    }

    /** Returns "off" / "cancellation" / "awareness" or null. */
    public static String parseAncMode(Packet p) {
        if (p == null || !p.isCommand(CMD_ANC_READ)) return null;
        byte[] d = p.param(1);
        if (d.length != 2) return null;
        int m = d[1] & 0xff;
        return m < ANC_MODES.length ? ANC_MODES[m] : null;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
