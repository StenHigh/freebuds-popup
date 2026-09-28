import SwiftUI

final class PopupModel: ObservableObject {
    @Published var title: String
    @Published var status = "Подключено"
    @Published var anc: HuaweiSpp.Anc?
    @Published var battery: HuaweiSpp.Battery?
    @Published var loading = true
    @Published var appeared = false

    init(title: String) { self.title = title }
}

struct PopupView: View {
    @ObservedObject var model: PopupModel
    var onClose: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.title).font(.system(size: 14, weight: .semibold))
                    Text([model.status, model.anc?.rawValue].compactMap { $0 }.joined(separator: "  ·  "))
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundStyle(.secondary)
                }.buttonStyle(.plain)
            }
            HStack(spacing: 0) {
                slot("Левый", level: levels.0, charging: model.battery?.leftCharging ?? false) { BudShape(left: true) }
                slot("Кейс", level: levels.1, charging: model.battery?.caseCharging ?? false) {
                    CaseShape(charging: model.battery?.caseCharging ?? false)
                }
                slot("Правый", level: levels.2, charging: model.battery?.rightCharging ?? false) { BudShape(left: false) }
            }
        }
        .padding(16)
        .frame(width: 340)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.white.opacity(0.08)))
    }

    /// nil = unknown/not provided; loading handled separately
    private var levels: (Int?, Int?, Int?) {
        guard let b = model.battery else { return (nil, nil, nil) }
        if b.left != nil { return (b.left, b.caseLevel, b.right) }
        return (b.global, nil, b.global)
    }

    @ViewBuilder
    private func slot<Icon: View>(_ label: String, level: Int?, charging: Bool,
                                  @ViewBuilder icon: () -> Icon) -> some View {
        VStack(spacing: 6) {
            icon()
                .frame(width: 60, height: 56)
                .scaleEffect(model.appeared ? 1 : 0.4)
                .opacity(model.appeared ? 1 : 0)
            Text(label).font(.system(size: 10)).foregroundStyle(.secondary)
            BatteryBar(level: level, charging: charging, loading: model.loading)
            Text(levelText(level, charging))
                .font(.system(size: 13, weight: .semibold)).monospacedDigit()
                .foregroundStyle(level == nil ? Color.secondary : Color.primary)
        }
        .frame(maxWidth: .infinity)
    }

    private func levelText(_ level: Int?, _ charging: Bool) -> String {
        if model.loading { return "…" }
        guard let level else { return "—" }
        return (charging ? "⚡" : "") + "\(level)%"
    }
}

struct BatteryBar: View {
    let level: Int?
    let charging: Bool
    let loading: Bool
    @State private var pulse = false

    var body: some View {
        ZStack(alignment: .leading) {
            Capsule().fill(Color.primary.opacity(0.12))
            if loading {
                Capsule().fill(Color.primary.opacity(pulse ? 0.25 : 0.05))
                    .onAppear { withAnimation(.easeInOut(duration: 0.6).repeatForever()) { pulse = true } }
            } else if let level {
                GeometryReader { g in
                    Capsule().fill(color(level))
                        .frame(width: max(3, g.size.width * CGFloat(min(level, 100)) / 100))
                }
            }
        }
        .frame(width: 50, height: 5)
        .animation(.easeOut(duration: 0.6), value: level)
    }

    private func color(_ l: Int) -> Color {
        if charging { return .green }
        if l <= 15 { return .red }
        if l <= 30 { return .orange }
        return .green
    }
}

struct BudShape: View {
    let left: Bool
    var body: some View {
        let dir: CGFloat = left ? 1 : -1
        ZStack {
            RoundedRectangle(cornerRadius: 5).fill(.white)
                .overlay(RoundedRectangle(cornerRadius: 5).stroke(.gray.opacity(0.5), lineWidth: 1))
                .frame(width: 10, height: 34).offset(x: dir * 2, y: 10)
            Circle().fill(.white).overlay(Circle().stroke(.gray.opacity(0.5), lineWidth: 1))
                .frame(width: 28, height: 28).offset(x: -dir * 5, y: -10)
            Circle().fill(.gray.opacity(0.6)).frame(width: 10, height: 10).offset(x: -dir * 13, y: -12)
        }
    }
}

struct CaseShape: View {
    let charging: Bool
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.white)
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(.gray.opacity(0.5), lineWidth: 1))
                .frame(width: 50, height: 40)
            Rectangle().fill(.gray.opacity(0.5)).frame(width: 46, height: 1).offset(y: -5)
            Circle().fill(charging ? Color.green : Color.gray.opacity(0.6)).frame(width: 4, height: 4).offset(y: 9)
        }
    }
}
