import Foundation
import SwiftUI
import UIKit
import WebKit

struct DuoComputerExecution: Decodable, Equatable, Identifiable {
    let id: String
    let command: String
    let cwd: String
    let startedAt: Int64
    let output: String
    let completedAt: Int64?
    let exitCode: Int?
    let error: String
    let trimmed: Bool

    private enum CodingKeys: String, CodingKey {
        case id, command, cwd, startedAt, output, completedAt, exitCode, error, trimmed
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        command = try values.decode(String.self, forKey: .command)
        cwd = try values.decode(String.self, forKey: .cwd)
        startedAt = try values.decode(Int64.self, forKey: .startedAt)
        output = try values.decodeIfPresent(String.self, forKey: .output) ?? ""
        completedAt = try values.decodeIfPresent(Int64.self, forKey: .completedAt)
        exitCode = try values.decodeIfPresent(Int.self, forKey: .exitCode)
        error = try values.decodeIfPresent(String.self, forKey: .error) ?? ""
        trimmed = try values.decodeIfPresent(Bool.self, forKey: .trimmed) ?? false
    }
}

struct DuoComputerSnapshot: Decodable, Equatable {
    var sessionId = ""
    var directory = "/workspace"
    var runtimeReady = false
    var executions: [DuoComputerExecution] = []
    var browserSessionId = ""
    var browserActive = false
    var browserReady = false
    var browserUpdatedAt: Int64 = 0
    var browserURL = ""

    var showsBrowser: Bool {
        browserActive && browserReady && browserSessionId == sessionId && !sessionId.isEmpty &&
            browserUpdatedAt > (executions.map(\.startedAt).max() ?? 0)
    }
}

@MainActor
final class DuoAgentComputerModel: ObservableObject {
    @Published private(set) var snapshot = DuoComputerSnapshot()
    @Published private(set) var isVisible = false

    func receive(_ json: String) {
        guard let data = json.data(using: .utf8),
              let value = try? JSONDecoder().decode(DuoComputerSnapshot.self, from: data),
              value != snapshot else { return }
        snapshot = value
    }

    func updatePresentation(isDuo: Bool, halfFolded: Bool, chatVisible: Bool) {
        let visible = isDuo && halfFolded && chatVisible
        if visible != isVisible { isVisible = visible }
    }
}

/// Only an active vertical division in the current Duo window can reserve a pane.
struct DuoComputerLayout: Equatable {
    let chat: CGRect
    let division: CGRect
    let computer: CGRect

    static func resolve(bounds: CGRect, activeDivision: CGRect?, isDuo: Bool, chatVisible: Bool) -> Self? {
        guard isDuo, chatVisible, let divider = activeDivision?.intersection(bounds),
              !divider.isNull, divider.height > divider.width,
              divider.minX > bounds.minX, divider.maxX < bounds.maxX else { return nil }
        return Self(
            chat: CGRect(x: bounds.minX, y: bounds.minY, width: divider.minX - bounds.minX, height: bounds.height),
            division: CGRect(x: divider.minX, y: bounds.minY, width: divider.width, height: bounds.height),
            computer: CGRect(x: divider.maxX, y: bounds.minY, width: bounds.maxX - divider.maxX, height: bounds.height)
        )
    }
}

@available(iOS 18.0, *)
struct DuoAgentComputerView: View {
    @ObservedObject var model: DuoAgentComputerModel
    @ObservedObject var settings: NativeSettingsModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var systemColorScheme

    var body: some View {
        let snapshot = model.snapshot
        let browser = snapshot.showsBrowser
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(settings.text("Agent’s computer", "Agent 的电脑"))
                        .font(.headline)
                    Text(settings.text("Read only", "只读"))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Label(browser ? "Chrome" : settings.text("Terminal", "终端"), systemImage: browser ? "globe" : "terminal")
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("duoComputerActivity")
            }
            .padding(16)
            Divider()
            GeometryReader { geometry in
                ZStack {
                    DuoComputerTerminal(snapshot: snapshot, settings: settings)
                        .id(snapshot.sessionId)
                        .offset(x: browser && !reduceMotion ? -geometry.size.width : 0)
                        .opacity(browser ? 0 : 1)
                        .allowsHitTesting(!browser)
                        .accessibilityHidden(browser)
                    if model.isVisible && snapshot.browserActive && snapshot.browserSessionId == snapshot.sessionId {
                        DuoComputerBrowser()
                            .offset(x: !browser && !reduceMotion ? geometry.size.width : 0)
                            .opacity(browser ? 1 : 0)
                            .accessibilityHidden(!browser)
                            .allowsHitTesting(false)
                    }
                }
                .clipped()
                .animation(reduceMotion ? .easeOut(duration: 0.1) : .smooth(duration: 0.28), value: browser)
            }
            .background(Color(red: 0.067, green: 0.075, blue: 0.09))
        }
        .background(.background)
        .environment(\.colorScheme, settings.themeMode == "dark" ? .dark : settings.themeMode == "light" ? .light : systemColorScheme)
        .environment(\.layoutDirection, settings.language == "fa" ? .rightToLeft : .leftToRight)
        .ignoresSafeArea(.keyboard)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("duoAgentComputer")
    }
}

@available(iOS 18.0, *)
private struct DuoComputerTerminal: View {
    let snapshot: DuoComputerSnapshot
    @ObservedObject var settings: NativeSettingsModel
    @State private var followsOutput = true
    @State private var nearBottom = true
    private let promptColor = Color(red: 0.62, green: 0.85, blue: 0.74)

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if !snapshot.runtimeReady {
                        Text(settings.text("Alpine is not ready. Initialize it in Settings.", "Alpine 尚未就绪，请在设置中初始化。"))
                            .font(.caption).foregroundStyle(.gray)
                    }
                    ForEach(snapshot.executions) { execution in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(execution.cwd) $ \(execution.command)").foregroundStyle(promptColor)
                            if execution.trimmed {
                                Text(settings.text("Earlier output was trimmed.", "较早的输出已截断。"))
                                    .font(.caption2).foregroundStyle(.gray)
                            }
                            if !execution.output.isEmpty {
                                Text(execution.output).foregroundStyle(Color(white: 0.9))
                            }
                            if execution.completedAt != nil {
                                Text(execution.error.isEmpty && execution.exitCode != nil
                                     ? settings.text("Exit code \(execution.exitCode!)", "退出码 \(execution.exitCode!)")
                                     : settings.text("Execution interrupted", "执行已中断"))
                                    .font(.caption2)
                                    .foregroundStyle(execution.error.isEmpty && execution.exitCode == 0 ? .gray : .red)
                            }
                        }
                    }
                    if snapshot.executions.last?.completedAt != nil || snapshot.executions.isEmpty {
                        Text("\(snapshot.directory) $ ▍").foregroundStyle(promptColor)
                    }
                    Color.clear.frame(height: 1).id("terminalEnd")
                }
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentSize.height - geometry.contentOffset.y - geometry.containerSize.height < 64
            } action: { _, value in
                nearBottom = value
            }
            .onScrollPhaseChange { _, phase in
                if phase == .interacting || phase == .decelerating { followsOutput = false }
                if phase == .idle { followsOutput = nearBottom }
            }
            .onChange(of: snapshot.executions) { _, _ in
                if followsOutput { proxy.scrollTo("terminalEnd", anchor: .bottom) }
            }
            .onAppear { proxy.scrollTo("terminalEnd", anchor: .bottom) }
        }
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityIdentifier("duoComputerTerminal")
    }
}

/// The existing Alpine noVNC observer is view-only at the protocol level as well.
private struct DuoComputerBrowser: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView(frame: .zero)
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.isUserInteractionEnabled = false
        webView.scrollView.isScrollEnabled = false
        webView.accessibilityLabel = "Agent’s Chrome, read only"
        webView.load(URLRequest(url: URL(string: "http://127.0.0.1:6080/aether-observer.html")!))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: ()) {
        uiView.stopLoading()
        uiView.loadHTMLString("", baseURL: nil)
    }
}
