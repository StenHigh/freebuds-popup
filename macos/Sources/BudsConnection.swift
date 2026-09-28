import Foundation
import IOBluetooth

/// One SPP session with the earbuds: opens RFCOMM, asks for battery + ANC,
/// keeps listening for push updates until `close()`.
final class BudsConnection: NSObject, IOBluetoothRFCOMMChannelDelegate {
    private let device: IOBluetoothDevice
    private var channel: IOBluetoothRFCOMMChannel?
    private var frames = HuaweiSpp.FrameBuffer()

    var onBattery: ((HuaweiSpp.Battery) -> Void)?
    var onAnc: ((HuaweiSpp.Anc) -> Void)?
    var onError: ((String) -> Void)?

    init(device: IOBluetoothDevice) { self.device = device }

    /// SPP channel from the SDP record if present, else channel 1 (what Huawei uses on 5i/6i).
    private func sppChannelID() -> BluetoothRFCOMMChannelID {
        if let uuid = IOBluetoothSDPUUID(uuid16: 0x1101),
           let rec = device.getServiceRecord(for: uuid) {
            var ch: BluetoothRFCOMMChannelID = 0
            if rec.getRFCOMMChannelID(&ch) == kIOReturnSuccess, ch != 0 { return ch }
        }
        return 1
    }

    func open() {
        let id = sppChannelID()
        var ch: IOBluetoothRFCOMMChannel?
        // Async open: result arrives in rfcommChannelOpenComplete.
        let r = device.openRFCOMMChannelAsync(&ch, withChannelID: id, delegate: self)
        if r != kIOReturnSuccess {
            onError?("RFCOMM open failed (\(String(format: "0x%08x", r)), channel \(id))")
            return
        }
        channel = ch
    }

    func close() {
        _ = channel?.setDelegate(nil)
        _ = channel?.close()
        channel = nil
    }

    private func send(_ p: HuaweiSpp.Packet) {
        guard let channel else { return }
        var bytes = p.toBytes()
        let r = bytes.withUnsafeMutableBytes { raw in
            channel.writeSync(raw.baseAddress, length: UInt16(raw.count))
        }
        if r != kIOReturnSuccess { onError?("write failed \(String(format: "0x%08x", r))") }
    }

    // MARK: IOBluetoothRFCOMMChannelDelegate

    func rfcommChannelOpenComplete(_ rfcommChannel: IOBluetoothRFCOMMChannel!, status error: IOReturn) {
        guard error == kIOReturnSuccess else {
            onError?("RFCOMM open status \(String(format: "0x%08x", error))")
            return
        }
        channel = rfcommChannel
        send(HuaweiSpp.batteryRequest)
        send(HuaweiSpp.ancRequest)
    }

    func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!,
                           data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        guard let dataPointer else { return }
        let chunk = Array(UnsafeRawBufferPointer(start: dataPointer, count: dataLength))
        for p in frames.feed(chunk) {
            if let b = HuaweiSpp.parseBattery(p) { onBattery?(b) }
            if let a = HuaweiSpp.parseAnc(p) { onAnc?(a) }
        }
    }

    func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
        channel = nil
    }
}
