package by.dzianis.budspopup.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static by.dzianis.budspopup.protocol.HuaweiSpp.*;

/**
 * Plain-Java tests (no JUnit dependency so they also run with bare javac).
 * Vectors come from OpenFreebuds (real device captures in test_battery.py)
 * and from its Python implementation.
 */
public class HuaweiSppTest {

    static int passed = 0;

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("ok  " + name);
    }

    public static void main(String[] args) throws Exception {
        // CRC-16/XMODEM check value
        byte[] nine = "123456789".getBytes("ASCII");
        check(crc16Xmodem(nine, 0, nine.length) == 0x31c3, "crc16 xmodem check value");

        // Requests must be byte-identical to OpenFreebuds
        check(hex(batteryRequest().toBytes()).equals("5a0009000108010002000300fbb9"), "battery request bytes");
        check(hex(ancRequest().toBytes()).equals("5a0007002b2a010002001d33"), "anc request bytes");

        // Real device response (TWS)
        Packet p = parse(unhex("5a0014000108010140020310203003030001000402140a1461"));
        check(p != null, "parse real battery response");
        Battery b = parseBattery(p);
        check(b.global == 0x40 && b.left == 0x10 && b.right == 0x20 && b.caseLevel == 0x30, "battery levels");
        check(b.anyCharging && !b.leftCharging && b.rightCharging && !b.caseCharging, "charging flags");

        // Legacy single-level device
        Battery legacy = parseBattery(parse(unhex("5a0009000108010140030100ed2e")));
        check(legacy.global == 0x40 && !legacy.hasTws() && !legacy.anyCharging, "legacy battery");

        // Python-generated frame round trip
        String py = "5a001000010801013702033c325003030000018e02";
        Packet rt = new Packet(CMD_BATTERY_READ)
                .param(1, new byte[]{55})
                .param(2, new byte[]{60, 50, 80})
                .param(3, new byte[]{0, 0, 1});
        check(hex(rt.toBytes()).equals(py), "build == python");
        Battery b2 = parseBattery(parse(unhex(py)));
        check(b2.left == 60 && b2.right == 50 && b2.caseLevel == 80 && b2.caseCharging, "parse python frame");

        // Corrupted CRC is rejected
        byte[] bad = unhex(py);
        bad[bad.length - 1] ^= 1;
        check(parse(bad) == null, "bad crc rejected");

        // Stream reader: garbage + two frames back to back
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(new byte[]{0x00, 0x13, 0x37});
        s.write(unhex("5a0009000108010140030100ed2e"));
        s.write(unhex("5a0014000108010140020310203003030001000402140a1461"));
        ByteArrayInputStream in = new ByteArrayInputStream(s.toByteArray());
        Packet f1 = readFrame(in), f2 = readFrame(in), f3 = readFrame(in);
        check(f1 != null && parseBattery(f1).global == 0x40 && !parseBattery(f1).hasTws(), "stream frame 1");
        check(f2 != null && parseBattery(f2).left == 0x10, "stream frame 2");
        check(f3 == null, "stream EOF");

        // ANC
        Packet anc = new Packet(CMD_ANC_READ).param(1, new byte[]{3, 1});
        check("cancellation".equals(parseAncMode(parse(anc.toBytes()))), "anc mode parse");

        System.out.println("\nAll " + passed + " checks passed");
    }
}
