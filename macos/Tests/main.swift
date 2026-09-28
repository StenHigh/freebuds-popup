// swiftc Sources/HuaweiSpp.swift Tests/main.swift -o build/tests && ./build/tests
import Foundation

func unhex(_ s: String) -> [UInt8] {
    var out: [UInt8] = []; var i = s.startIndex
    while i < s.endIndex { let j = s.index(i, offsetBy: 2); out.append(UInt8(s[i..<j], radix: 16)!); i = j }
    return out
}
func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

var passed = 0
func check(_ c: Bool, _ name: String) {
    if !c { print("FAIL: \(name)"); exit(1) }
    passed += 1; print("ok  \(name)")
}

check(HuaweiSpp.crc16(Array("123456789".utf8)) == 0x31c3, "crc16 xmodem")
check(hex(HuaweiSpp.batteryRequest.toBytes()) == "5a0009000108010002000300fbb9", "battery request")
check(hex(HuaweiSpp.ancRequest.toBytes()) == "5a0007002b2a010002001d33", "anc request")

let real = HuaweiSpp.parse(unhex("5a0014000108010140020310203003030001000402140a1461"))
check(real != nil, "parse real response")
let b = HuaweiSpp.parseBattery(real!)!
check(b.global == 0x40 && b.left == 0x10 && b.right == 0x20 && b.caseLevel == 0x30, "levels")
check(!b.leftCharging && b.rightCharging && !b.caseCharging, "charging flags")

var bad = unhex("5a001000010801013702033c325003030000018e02"); bad[bad.count - 1] ^= 1
check(HuaweiSpp.parse(bad) == nil, "bad crc rejected")

var fb = HuaweiSpp.FrameBuffer()
let stream = [0x00, 0x13] + unhex("5a0009000108010140030100ed2e") + unhex("5a00140001080101400203102030")
var got = fb.feed(stream)
check(got.count == 1 && HuaweiSpp.parseBattery(got[0])?.global == 0x40, "stream: first frame, second partial")
got = fb.feed(unhex("03030001000402140a1461"))
check(got.count == 1 && HuaweiSpp.parseBattery(got[0])?.left == 0x10, "stream: second frame completes")

let anc = HuaweiSpp.Packet(command: HuaweiSpp.cmdAncRead, params: [(1, [3, 1])])
check(HuaweiSpp.parseAnc(HuaweiSpp.parse(anc.toBytes())!) == .cancellation, "anc parse")

print("\nAll \(passed) checks passed")
