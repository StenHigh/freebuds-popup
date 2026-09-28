import Foundation

/// Huawei FreeBuds SPP protocol (port of OpenFreebuds, GPL-3.0).
/// Frame: 'Z' | len(2,BE)=body+1 | 0x00 | cmd(2) | [type len value]... | crc16-xmodem(2,BE)
enum HuaweiSpp {
    static let cmdBatteryRead: [UInt8] = [0x01, 0x08]
    static let cmdBatteryNotify: [UInt8] = [0x01, 0x27]
    static let cmdAncRead: [UInt8] = [0x2b, 0x2a]

    struct Packet {
        var command: [UInt8]
        var params: [(UInt8, [UInt8])] = []

        func param(_ t: UInt8) -> [UInt8] { params.first { $0.0 == t }?.1 ?? [] }
        func isCommand(_ c: [UInt8]) -> Bool { command == c }

        func toBytes() -> [UInt8] {
            var body = command
            for (t, v) in params { body += [t, UInt8(v.count)] + v }
            let len = body.count + 1
            var out: [UInt8] = [0x5A, UInt8((len >> 8) & 0xff), UInt8(len & 0xff), 0x00] + body
            let crc = HuaweiSpp.crc16(out)
            out += [UInt8(crc >> 8), UInt8(crc & 0xff)]
            return out
        }
    }

    static func readRequest(_ cmd: [UInt8], _ types: [UInt8]) -> Packet {
        Packet(command: cmd, params: types.map { ($0, []) })
    }

    static var batteryRequest: Packet { readRequest(cmdBatteryRead, [1, 2, 3]) }
    static var ancRequest: Packet { readRequest(cmdAncRead, [1, 2]) }

    static func crc16(_ data: [UInt8]) -> UInt16 {
        var crc: UInt16 = 0
        for b in data {
            crc ^= UInt16(b) << 8
            for _ in 0..<8 { crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
        }
        return crc
    }

    /// Stream reassembly: feed raw RFCOMM chunks, get complete, CRC-valid packets out.
    struct FrameBuffer {
        private var buf: [UInt8] = []

        mutating func feed(_ chunk: [UInt8]) -> [Packet] {
            buf += chunk
            var out: [Packet] = []
            while true {
                guard let z = buf.firstIndex(of: 0x5A) else { buf.removeAll(); break }
                if z > 0 { buf.removeFirst(z) }
                guard buf.count >= 3 else { break }
                let length = Int(buf[1]) << 8 | Int(buf[2])
                if length < 3 || length > 4096 { buf.removeFirst(); continue }
                let total = 3 + length + 2
                guard buf.count >= total else { break }
                let frame = Array(buf[0..<total])
                if let p = HuaweiSpp.parse(frame) {
                    out.append(p)
                    buf.removeFirst(total)
                } else {
                    buf.removeFirst() // resync
                }
            }
            return out
        }
    }

    static func parse(_ d: [UInt8]) -> Packet? {
        guard d.count >= 8, d[0] == 0x5A, d[3] == 0 else { return nil }
        let length = Int(d[1]) << 8 | Int(d[2])
        let total = 3 + length + 2
        guard d.count >= total else { return nil }
        let crc = crc16(Array(d[0..<(total - 2)]))
        guard crc == (UInt16(d[total - 2]) << 8 | UInt16(d[total - 1])) else { return nil }
        var p = Packet(command: [d[4], d[5]])
        var pos = 6
        let end = 3 + length
        while pos + 1 < end {
            let t = d[pos], l = Int(d[pos + 1])
            guard pos + 2 + l <= end else { return nil }
            p.params.append((t, Array(d[(pos + 2)..<(pos + 2 + l)])))
            pos += 2 + l
        }
        return p
    }

    struct Battery {
        var global: Int? = nil
        var left: Int? = nil, right: Int? = nil, caseLevel: Int? = nil
        var leftCharging = false, rightCharging = false, caseCharging = false
    }

    static func parseBattery(_ p: Packet) -> Battery? {
        guard p.isCommand(cmdBatteryRead) || p.isCommand(cmdBatteryNotify) else { return nil }
        var b = Battery()
        let g = p.param(1); if g.count == 1 { b.global = Int(g[0]) }
        let t = p.param(2)
        if t.count == 3 { b.left = Int(t[0]); b.right = Int(t[1]); b.caseLevel = Int(t[2]) }
        let c = p.param(3)
        if c.count == 3 { b.leftCharging = c[0] == 1; b.rightCharging = c[1] == 1; b.caseCharging = c[2] == 1 }
        else if c.count == 1 { b.caseCharging = c[0] == 1 }
        return b
    }

    enum Anc: String { case off = "ANC выкл.", cancellation = "Шумоподавление", awareness = "Прозрачность" }

    static func parseAnc(_ p: Packet) -> Anc? {
        guard p.isCommand(cmdAncRead) else { return nil }
        let d = p.param(1)
        guard d.count == 2 else { return nil }
        switch d[1] { case 0: return .off; case 1: return .cancellation; case 2: return .awareness; default: return nil }
    }
}
