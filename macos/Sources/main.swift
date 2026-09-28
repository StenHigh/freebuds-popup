import AppKit
import IOBluetooth
import ServiceManagement
import SwiftUI

// Menu bar app: shows a card when FreeBuds connect to this Mac.

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private var connectNote: IOBluetoothUserNotification?
    private var lastShown: [String: Date] = [:]
    private let launchedAt = Date()
    private var lastBatteryText = "Нет данных"

    private var panel: NSPanel?
    private var model: PopupModel?
    private var conn: BudsConnection?
    private var hideWork: DispatchWorkItem?

    func applicationDidFinishLaunching(_ n: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "earbuds", accessibilityDescription: "Buds Popup")
            ?? NSImage(systemSymbolName: "headphones", accessibilityDescription: "Buds Popup")
        rebuildMenu()

        connectNote = IOBluetoothDevice.register(forConnectNotifications: self,
                                                  selector: #selector(deviceConnected(_:device:)))
    }

    // MARK: menu

    private func rebuildMenu() {
        let m = NSMenu()
        m.addItem(withTitle: lastBatteryText, action: nil, keyEquivalent: "")
        m.addItem(.separator())
        m.addItem(withTitle: "Показать карточку", action: #selector(showNow), keyEquivalent: "b").target = self
        let login = NSMenuItem(title: "Запускать при входе", action: #selector(toggleLogin), keyEquivalent: "")
        login.target = self
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        m.addItem(login)
        m.addItem(.separator())
        m.addItem(withTitle: "Выход", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        statusItem.menu = m
    }

    @objc private func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() }
            else { try SMAppService.mainApp.register() }
        } catch {
            let a = NSAlert()
            a.messageText = "Не получилось изменить автозапуск"
            a.informativeText = "\(error.localizedDescription)\nПеренеси приложение в /Applications и попробуй снова."
            a.runModal()
        }
        rebuildMenu()
    }

    @objc private func showNow() {
        let devs = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        if let d = devs.first(where: { $0.isConnected() && Self.isFreeBuds($0) }) {
            show(for: d)
        } else {
            let a = NSAlert()
            a.messageText = "FreeBuds не подключены к этому Mac"
            a.runModal()
        }
    }

    // MARK: bluetooth

    static func isFreeBuds(_ d: IOBluetoothDevice) -> Bool {
        let n = (d.name ?? "").lowercased()
        return n.contains("freebuds") || n.contains("freeclip") || n.contains("freelace")
    }

    @objc private func deviceConnected(_ note: IOBluetoothUserNotification, device: IOBluetoothDevice) {
        // IOBluetooth also reports already-connected devices right after registering: skip those.
        guard Date().timeIntervalSince(launchedAt) > 2 else { return }
        // Give A2DP a moment before opening the control channel; the name may also arrive late.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            guard Self.isFreeBuds(device) else { return }
            let key = device.addressString ?? "?"
            if let t = self.lastShown[key], Date().timeIntervalSince(t) < 15 { return }
            self.lastShown[key] = Date()
            self.show(for: device)
        }
    }

    // MARK: popup

    private func show(for device: IOBluetoothDevice) {
        dismiss(animated: false)

        let m = PopupModel(title: device.name ?? "FreeBuds")
        model = m
        let host = NSHostingView(rootView: PopupView(model: m, onClose: { [weak self] in self?.dismiss(animated: true) }))
        host.layout()
        let size = host.fittingSize

        let p = NSPanel(contentRect: NSRect(origin: .zero, size: size),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.level = .statusBar
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        p.contentView = host
        panel = p

        // top-right, under the menu bar — where macOS puts its own notifications
        let screen = NSScreen.main ?? NSScreen.screens[0]
        let vf = screen.visibleFrame
        let finalOrigin = NSPoint(x: vf.maxX - size.width - 12, y: vf.maxY - size.height - 12)
        p.setFrameOrigin(NSPoint(x: finalOrigin.x + 40, y: finalOrigin.y))
        p.alphaValue = 0
        p.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.35
            ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
            p.animator().setFrameOrigin(finalOrigin)
            p.animator().alphaValue = 1
        }
        withAnimation(.spring(response: 0.45, dampingFraction: 0.6).delay(0.1)) { m.appeared = true }

        let c = BudsConnection(device: device)
        conn = c
        c.onBattery = { [weak self, weak m] b in
            DispatchQueue.main.async {
                guard let self, let m else { return }
                let first = m.loading
                m.loading = false
                m.battery = b
                self.lastBatteryText = Self.describe(b)
                self.rebuildMenu()
                if first { self.scheduleHide(after: 5) }
            }
        }
        c.onAnc = { [weak m] a in DispatchQueue.main.async { m?.anc = a } }
        c.onError = { [weak self, weak m] err in
            DispatchQueue.main.async {
                NSLog("BudsPopup: \(err)")
                m?.loading = false
                m?.status = "Подключено (нет данных о заряде)"
                self?.scheduleHide(after: 3)
            }
        }
        c.open()
        scheduleHide(after: 9) // hard limit
    }

    private func scheduleHide(after s: Double) {
        hideWork?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.dismiss(animated: true) }
        hideWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + s, execute: w)
    }

    private func dismiss(animated: Bool) {
        hideWork?.cancel(); hideWork = nil
        conn?.close(); conn = nil
        guard let p = panel else { return }
        panel = nil; model = nil
        if !animated { p.orderOut(nil); return }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.25
            p.animator().alphaValue = 0
            p.animator().setFrameOrigin(NSPoint(x: p.frame.origin.x + 40, y: p.frame.origin.y))
        }, completionHandler: { p.orderOut(nil) })
    }

    static func describe(_ b: HuaweiSpp.Battery) -> String {
        func f(_ v: Int?, _ ch: Bool) -> String { v.map { (ch ? "⚡" : "") + "\($0)%" } ?? "—" }
        if b.left != nil {
            return "L \(f(b.left, b.leftCharging))   R \(f(b.right, b.rightCharging))   Кейс \(f(b.caseLevel, b.caseCharging))"
        }
        return "Заряд \(f(b.global, false))"
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
