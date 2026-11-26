import Combine
import Foundation
import Security
import SwiftUI
import UIKit

/// A TV box as the Devices screen shows it.
struct TVBox: Identifiable, Equatable {
    let id: String
    var name: String
    var host: String?
    var port: UInt16
    var isPaired: Bool
    var isDiscovered: Bool
    var isManual: Bool
    var receiverHost: String?
    var receiverPort: UInt16?
    /// The app connected to this box before, or the box is paired. The device list shows it on top, highlighted.
    var isKnown = false

    var mouseReady: Bool { receiverHost != nil }

    /// The order of the device list: the boxes that the app connected to come first, the most recent first
    /// (`recent`, the box ids). After them come the other paired boxes, then the rest. Each group is sorted by name.
    /// The result has `isKnown` set.
    static func knownFirst(_ boxes: [TVBox], recent: [String]) -> [TVBox] {
        func rank(_ box: TVBox) -> Int {
            if let index = recent.firstIndex(of: box.id) { return index }
            return box.isPaired ? recent.count : recent.count + 1
        }
        return boxes
            .map { box -> (rank: Int, box: TVBox) in
                var known = box
                let r = rank(box)
                known.isKnown = r <= recent.count
                return (r, known)
            }
            .sorted { a, b in
                if a.rank != b.rank { return a.rank < b.rank }
                return a.box.name.localizedCaseInsensitiveCompare(b.box.name) == .orderedAscending
            }
            .map(\.box)
    }
}

/// The state of the whole app. It runs on the main actor.
@MainActor
final class AppModel: ObservableObject {
    enum ConnectionState: Equatable {
        case idle
        case connecting
        case connected
        case failed(String)
    }

    enum MouseState: Equatable {
        case off
        case connecting
        case ready
        case needsPairing
        case unavailable(String)
    }

    enum Sheet: String, Identifiable {
        case devices, mediaKeys, keyboard, preferences, help, about, atvPairing
        var id: String { rawValue }
    }

    enum PairingStage: Equatable {
        case waitingForTV
        case enterCode
        case checking
        case failed
    }

    static var noReceiverMessage: String { L10n.t("mouse.noReceiver") }

    @Published private(set) var boxes: [TVBox] = []
    @Published private(set) var currentBox: TVBox?
    @Published private(set) var connectionState: ConnectionState = .idle
    @Published var sheet: Sheet? {
        didSet { sheetChanged(from: oldValue) }
    }
    @Published var showMouse = false
    @Published var showController = false
    @Published private(set) var pairingStage: PairingStage = .waitingForTV
    @Published var pairingError: String?
    @Published private(set) var pairingBoxName = ""
    @Published private(set) var mouseState: MouseState = .off
    @Published var receiverPairingVisible = false
    @Published var receiverPairingError: String?
    @Published private(set) var volume: VolumeInfo?
    @Published private(set) var boxIsOn: Bool?
    @Published var keyboardText = ""
    /// "Type link on the box" on the mouse screen.
    @Published private(set) var linkTyping: LinkTyping = .idle
    @Published var alertMessage: String?

    let prefs = Preferences()

    /// Fires once for each command that went to the box (a key, a text change,
    /// a mouse action). The signal line on the remote screen shows a pulse for each.
    let signals = PassthroughSubject<Void, Never>()

    private let store = BoxStore()
    private let discovery = DeviceDiscovery()
    private let gyro = GyroPointer()
    private var session: AtvRemoteSession?
    private var pairing: AtvPairing?
    private var receiver: ReceiverClient?
    private var reconnectAttempts = 0
    /// True from a new pairing until the first session works. A box that has just paired can refuse the first
    /// connection for a moment, so the app tries more often then, and never pairs again on its own.
    private var freshlyPaired = false
    private var gyroWanted = false

    private var phoneName: String { UIDevice.current.name }

    init() {
        discovery.onChange = { [weak self] in
            self?.rebuildBoxes()
        }
        gyro.onMove = { [weak self] dx, dy in
            self?.receiver?.move(dx: Double(dx), dy: Double(dy))
        }
        // Make the client certificate early. RSA key generation takes a moment.
        Task.detached(priority: .utility) {
            _ = try? CertificateManager.shared.identity()
        }
        discovery.start()
        rebuildBoxes()
        restoreLastBox()
        setUpTours()
    }

    // MARK: - Tours (docs/UI_SPEC.md, "First-run tutorial")

    /// The first-run tour.
    @Published private(set) var tour: Tour?
    /// The mouse walk-through, the first time the mouse screen opens.
    @Published private(set) var mouseTour: MouseTour?
    /// The receiver install guide on the mouse screen.
    @Published var mouseGuideOpen = false

    private static let tourDoneKey = "tour.done"
    private static let mouseTourDoneKey = "tour.mouseDone"
    private var tourDefaults: UserDefaults { SharedConfig.defaults }

    /// The tour starts on the first launch only. A phone that paired before (an update) skips both tours.
    private func setUpTours() {
        if !tourDefaults.bool(forKey: Self.mouseTourDoneKey), store.all.contains(where: { $0.receiverToken != nil }) {
            tourDefaults.set(true, forKey: Self.mouseTourDoneKey)
        }
        tour = TourFlow.initial(
            tourDone: tourDefaults.bool(forKey: Self.tourDoneKey),
            hasPairedBox: store.all.contains { $0.fingerprint != nil }
        )
        if tour == nil { tourDefaults.set(true, forKey: Self.tourDoneKey) }
    }

    func finishTour() {
        tour = nil
        tourDefaults.set(true, forKey: Self.tourDoneKey)
    }

    /// Help, "Show the tutorial again". Help closes first.
    func replayTour() {
        sheet = nil
        tour = .pair
    }

    /// Called when the mouse screen shows the setup. The walk-through runs the first time only.
    func startMouseTourIfFirst() {
        guard !tourDefaults.bool(forKey: Self.mouseTourDoneKey), mouseTour == nil, tour == nil else { return }
        mouseTour = .choose
    }

    func finishMouseTour() {
        mouseTour = nil
        tourDefaults.set(true, forKey: Self.mouseTourDoneKey)
    }

    /// Help, "Show the mouse guide again": Help closes, then the mouse screen opens with the guide.
    func replayMouseGuide() {
        sheet = nil
        Task { [weak self] in
            // The sheet must be gone before a full screen cover can open.
            try? await Task.sleep(nanoseconds: 450_000_000)
            guard let self else { return }
            guard self.currentBox != nil else {
                self.sheet = .devices
                return
            }
            self.openMouse()
            self.mouseGuideOpen = true
        }
    }

    /// The receiver install guide looks for the receiver while the user installs and turns it on.
    func lookForReceiver() {
        guard let box = currentBox, case .unavailable = mouseState else { return }
        receiver?.close()
        receiver = nil
        connectReceiver(box)
    }

    // MARK: - Status

    var statusTitle: String {
        currentBox?.name ?? L10n.t("status.notConnected")
    }

    var statusDetail: String? {
        switch connectionState {
        case .connecting: return L10n.t("status.connecting")
        case .failed: return L10n.t("status.tapToReconnect")
        default: return nil
        }
    }

    // MARK: - Devices

    func connect(_ box: TVBox) {
        guard let host = box.host else {
            alertMessage = L10n.t("alert.noAddress")
            return
        }
        teardown()
        currentBox = box
        store.lastBoxId = box.id
        reconnectAttempts = 0
        if let fingerprint = store.box(box.id)?.fingerprint {
            if sheet == .devices { sheet = nil }
            startSession(host: host, port: box.port, fingerprint: fingerprint)
        } else {
            startPairing(box: box, host: host)
        }
    }

    func reconnect() {
        guard let box = currentBox else {
            sheet = .devices
            return
        }
        connect(box)
    }

    func disconnect() {
        teardown()
        currentBox = nil
        store.lastBoxId = nil
    }

    func forget(_ box: TVBox) {
        if currentBox?.id == box.id {
            teardown()
            currentBox = nil
        }
        store.remove(box.id)
        rebuildBoxes()
    }

    /// Adds a box by IP address and connects to it. Returns false if the address is not valid.
    @discardableResult
    func addManualBox(ip: String) -> Bool {
        let address = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidIPv4(address) else { return false }
        let id = "ip:\(address)"
        if store.box(id) == nil {
            store.save(SavedBox(
                id: id,
                name: address,
                host: address,
                port: AtvRemoteSession.defaultPort,
                fingerprint: nil,
                receiverToken: nil,
                isManual: true
            ))
        }
        rebuildBoxes()
        if let box = boxes.first(where: { $0.id == id }) {
            connect(box)
        }
        return true
    }

    nonisolated static func isValidIPv4(_ text: String) -> Bool {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        return parts.allSatisfy { part in
            guard !part.isEmpty, part.count <= 3, let value = Int(part) else { return false }
            return value >= 0 && value <= 255
        }
    }

    // MARK: - Android TV pairing

    func submitPairingCode(_ code: String) {
        guard let pairing else { return }
        switch pairing.submit(code: code) {
        case .sent:
            pairingStage = .checking
            pairingError = nil
        case .invalidFormat:
            pairingError = L10n.t("pair.invalidFormat")
        case .wrongCode:
            pairingError = L10n.t("pair.wrongCode")
        case .notReady:
            pairingError = L10n.t("pair.notReady")
        }
    }

    func retryPairing() {
        guard let box = currentBox, let host = box.host else { return }
        pairing?.cancel()
        pairing = nil
        startPairing(box: box, host: host)
    }

    func cancelPairing() {
        pairing?.cancel()
        pairing = nil
        if sheet == .atvPairing { sheet = nil }
        if connectionState != .connected {
            connectionState = .idle
            currentBox = nil
        }
    }

    private func startPairing(box: TVBox, host: String) {
        guard let identity = clientIdentity(),
              let certificate = try? CertificateManager.shared.clientCertificate(),
              let numbers = try? Certificates.publicNumbers(certificate)
        else {
            connectionState = .failed(L10n.t("error.cert.cannotMake"))
            return
        }
        pairingBoxName = box.name
        pairingStage = .waitingForTV
        pairingError = nil
        connectionState = .connecting
        let pairing = AtvPairing(host: host, identity: identity, clientNumbers: numbers, clientName: phoneName)
        pairing.onAwaitingCode = { [weak self] in
            self?.pairingStage = .enterCode
        }
        pairing.onFinished = { [weak self] outcome in
            self?.pairingFinished(outcome, box: box, host: host)
        }
        self.pairing = pairing
        sheet = .atvPairing
        pairing.start()
    }

    private func pairingFinished(_ outcome: AtvPairing.Outcome, box: TVBox, host: String) {
        pairing = nil
        switch outcome {
        case .paired(let fingerprint):
            var saved = store.box(box.id) ?? SavedBox(
                id: box.id,
                name: box.name,
                host: host,
                port: box.port,
                fingerprint: nil,
                receiverToken: nil,
                isManual: box.isManual
            )
            saved.fingerprint = fingerprint
            saved.host = host
            store.save(saved)
            store.lastBoxId = box.id
            rebuildBoxes()
            sheet = nil
            freshlyPaired = true
            reconnectAttempts = 0
            startSession(host: host, port: box.port, fingerprint: fingerprint)
        case .failed(let message):
            pairingStage = .failed
            pairingError = message
            connectionState = .failed(message)
        }
    }

    private func sheetChanged(from oldValue: Sheet?) {
        // The user closed the pairing sheet: stop pairing.
        if oldValue == .atvPairing, sheet != .atvPairing, pairing != nil {
            cancelPairing()
        }
    }

    // MARK: - Remote session

    private func startSession(host: String, port: UInt16, fingerprint: String) {
        guard let identity = clientIdentity() else {
            connectionState = .failed(L10n.t("error.cert.cannotLoad"))
            return
        }
        session?.disconnect()
        let session = AtvRemoteSession(
            host: host,
            port: port,
            identity: identity,
            pinnedFingerprint: fingerprint,
            model: UIDevice.current.model
        )
        session.onStateChange = { [weak self] state in
            self?.sessionStateChanged(state)
        }
        session.onVolume = { [weak self] info in
            self?.volume = info
        }
        session.onPower = { [weak self] isOn in
            self?.boxIsOn = isOn
        }
        session.onImeShow = { [weak self] text in
            self?.imeShown(text)
        }
        session.onImeFocus = { [weak self] in
            self?.imeFocused()
        }
        self.session = session
        connectionState = .connecting
        session.connect()
    }

    private func sessionStateChanged(_ state: AtvRemoteSession.State) {
        switch state {
        case .connecting:
            connectionState = .connecting
        case .connected:
            connectionState = .connected
            reconnectAttempts = 0
            freshlyPaired = false
            tour = TourFlow.onConnected(tour)
            // The widget lists the boxes that the app connected to, the most recent first.
            if let id = currentBox?.id {
                store.markConnected(id)
                // The box moves to the top of the device list.
                rebuildBoxes()
            }
        case .closed(let message):
            let mismatch = session?.certificateMismatch ?? false
            session = nil
            if mismatch {
                connectionState = .failed(L10n.t("error.session.certChanged"))
                return
            }
            // After a new pairing: 3 retries, 1, 2 and 3 seconds apart. Else 2 retries, 1.5 seconds apart.
            let maxAttempts = freshlyPaired ? 3 : 2
            guard reconnectAttempts < maxAttempts,
                  let box = currentBox,
                  let host = box.host,
                  let fingerprint = store.box(box.id)?.fingerprint
            else {
                connectionState = .failed(message ?? L10n.t("error.session.closed"))
                return
            }
            reconnectAttempts += 1
            connectionState = .connecting
            let wait: UInt64 = freshlyPaired ? UInt64(reconnectAttempts) * 1_000_000_000 : 1_500_000_000
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: wait)
                guard let self, self.session == nil, self.currentBox?.id == box.id else { return }
                self.startSession(host: host, port: box.port, fingerprint: fingerprint)
            }
        }
    }

    /// Returns true if a key can go to the box now. If not, it reconnects or opens Devices.
    private func requireConnection() -> Bool {
        switch connectionState {
        case .connected:
            return true
        case .connecting:
            return false
        case .idle, .failed:
            if currentBox != nil, pairing == nil {
                reconnect()
            } else {
                sheet = .devices
            }
            return false
        }
    }

    // MARK: - Keys and text

    func press(_ key: RemoteKey) {
        guard requireConnection() else { return }
        // NVIDIA SHIELD ignores the Settings key code, so open the settings with an app link there.
        if key == .settings, session?.isNvidia == true {
            session?.launch(appLink: atvSettingsLink)
        } else {
            session?.sendKey(key.rawValue)
        }
        signals.send()
    }

    func openKeyboard() {
        guard requireConnection() else { return }
        // The phone field starts empty, so the box gets only what the user types now.
        keyboardText = ""
        lastSentText = ""
        sheet = .keyboard
    }

    /// The text that the box has from the phone field. The box adds text at the cursor, so the phone sends
    /// one Delete for each removed character and then only the new characters.
    private var lastSentText = ""

    func sendText(_ text: String) {
        guard connectionState == .connected, let session else { return }
        let change = TextChange(from: lastSentText, to: text)
        for _ in 0..<change.deletes { session.sendKey(RemoteKey.delete.rawValue) }
        session.sendText(change.insert)
        lastSentText = text
        signals.send()
    }

    private func imeShown(_ text: String) {
        guard sheet == nil, !showMouse else { return }
        // The box field has this text already.
        lastSentText = text
        keyboardText = text
        sheet = .keyboard
    }

    // MARK: - Mouse

    func openMouse() {
        guard let box = currentBox else {
            sheet = .devices
            return
        }
        showMouse = true
        connectReceiver(box)
    }

    func closeMouse() {
        setGyro(false)
        receiver?.setCursor(visible: false)
        showMouse = false
    }

    // MARK: - Type the receiver link

    /// How long "Type link on the box" waits for the box keyboard.
    private static let linkWaitNanoseconds: UInt64 = 60_000_000_000
    private static let enterKey = 66
    private var linkTimeout: Task<Void, Never>?

    /// Types the receiver link into a text field on the box and presses Enter. The box takes text only for the field
    /// that has the focus while its keyboard is open, so the phone waits until the user selects the field on the box:
    /// then the box reports the field (`imeFocused`), and the phone types into it.
    func startTypeLink() {
        guard connectionState == .connected, session != nil else { return }
        linkTimeout?.cancel()
        linkTyping = .waiting
        linkTimeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.linkWaitNanoseconds)
            guard !Task.isCancelled, let self, self.linkTyping == .waiting else { return }
            self.linkTyping = .noKeyboard
        }
    }

    func closeTypeLink() {
        linkTimeout?.cancel()
        linkTyping = .idle
    }

    private func imeFocused() {
        guard linkTyping == .waiting else { return }
        linkTimeout?.cancel()
        linkTyping = .sending
        Task { [weak self] in
            // The box sends the field and then opens its keyboard. Let both arrive, so the counters are current.
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard let self, self.linkTyping == .sending, let session = self.session else { return }
            session.sendText(ReceiverDownload.url)
            try? await Task.sleep(nanoseconds: 300_000_000)
            session.sendKey(Self.enterKey)
            self.signals.send()
            self.linkTyping = .typed
        }
    }

    func retryMouse() {
        receiver?.close()
        receiver = nil
        guard let box = currentBox else {
            mouseState = .unavailable(Self.noReceiverMessage)
            return
        }
        // A refused connection fails at once. Show "Connecting" for a moment, so that Try again visibly works.
        mouseState = .connecting
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard let self, self.receiver == nil, self.showMouse, self.currentBox?.id == box.id else { return }
            self.connectReceiver(box)
        }
    }

    func setGyro(_ enabled: Bool) {
        gyroWanted = enabled
        if enabled {
            gyro.speed = prefs.gyroSpeed
            gyro.start()
        } else {
            gyro.stop()
        }
    }

    var gyroAvailable: Bool { gyro.isAvailable }

    func mouseMove(dx: Double, dy: Double) {
        receiver?.move(dx: dx, dy: dy)
    }

    func mouseClick() {
        guard mouseState == .ready else { return }
        receiver?.click()
        signals.send()
    }

    func mouseLongPress() {
        guard mouseState == .ready else { return }
        receiver?.longPress()
        signals.send()
    }

    func mouseScroll(dx: Double, dy: Double) {
        guard mouseState == .ready else { return }
        receiver?.scroll(dx: dx, dy: dy)
        signals.send()
    }

    func submitReceiverCode(_ code: String) {
        receiverPairingError = nil
        receiver?.pair(code: code)
    }

    func cancelReceiverPairing() {
        stopReceiver(because: L10n.t("receiver.cancelled"))
    }

    /// Closes the receiver connection and shows a message on the mouse screen.
    /// `close()` removes the event handler, so the socket close that follows
    /// cannot replace this message.
    private func stopReceiver(because message: String) {
        receiverPairingVisible = false
        receiver?.close()
        receiver = nil
        mouseState = .unavailable(message)
    }

    private func connectReceiver(_ box: TVBox) {
        if let receiver {
            if mouseState == .ready { receiver.setCursor(visible: true) }
            return
        }
        guard let host = box.receiverHost ?? box.host else {
            mouseState = .unavailable(Self.noReceiverMessage)
            return
        }
        let client = ReceiverClient(
            host: host,
            port: box.receiverPort ?? ReceiverClient.defaultPort,
            phoneName: phoneName,
            token: store.box(box.id)?.receiverToken
        )
        let boxId = box.id
        client.onEvent = { [weak self] event in
            self?.receiverEvent(event, boxId: boxId)
        }
        receiver = client
        mouseState = .connecting
        client.connect()
    }

    private func receiverEvent(_ event: ReceiverClient.Event, boxId: String) {
        switch event {
        case .pairRequired:
            mouseState = .needsPairing
            // Keep an error that is already on the sheet (for example "code expired").
            if !receiverPairingVisible {
                receiverPairingError = nil
            }
            receiverPairingVisible = true
        case .paired(let token):
            store.update(boxId) { $0.receiverToken = token }
        case .welcome:
            mouseState = .ready
            receiverPairingVisible = false
            receiver?.setCursor(visible: true)
        case .error(let code, let message):
            switch code {
            case "bad_code":
                receiverPairingError = L10n.t("pair.wrongCode")
            case "code_expired":
                receiverPairingError = L10n.t("receiver.codeExpired")
            case "pairing_locked":
                stopReceiver(because: L10n.t("receiver.locked"))
            case "too_many_clients":
                stopReceiver(because: L10n.t("receiver.full"))
            default:
                if receiverPairingVisible, !message.isEmpty {
                    receiverPairingError = message
                }
            }
        case .closed(let message):
            let wasReady = mouseState == .ready
            receiver = nil
            receiverPairingVisible = false
            if case .unavailable = mouseState { return }
            mouseState = .unavailable(wasReady ? (message ?? L10n.t("error.receiver.closed")) : Self.noReceiverMessage)
        }
    }

    // MARK: - Controller

    /// Opens the controller screen. The right stick moves the pointer through the
    /// receiver app, so the app connects to the receiver too. There is no pairing
    /// prompt here: without a paired receiver, the right stick shows as unavailable.
    func openController() {
        guard let box = currentBox else {
            sheet = .devices
            return
        }
        showController = true
        connectReceiver(box)
    }

    func closeController() {
        showController = false
        if !showMouse { receiver?.setCursor(visible: false) }
    }

    /// True when the right stick can move the pointer.
    var controllerPointerReady: Bool { mouseState == .ready }

    /// A controller button goes down or up. Over Wi-Fi this is a long press:
    /// START_LONG on press and END_LONG on release (docs/UI_SPEC.md, "Controller mode").
    func controllerKey(_ code: Int, down: Bool) {
        guard connectionState == .connected else {
            // No sheets here: the controller covers the screen. Reconnect quietly.
            if down, connectionState != .connecting, pairing == nil,
               let box = currentBox, store.box(box.id)?.fingerprint != nil {
                reconnect()
            }
            return
        }
        session?.sendKey(code, direction: down ? 1 : 2)
        if down { signals.send() }
    }

    /// A pointer move from the right stick, in TV pixels.
    func controllerPointer(dx: Double, dy: Double) {
        guard mouseState == .ready else { return }
        receiver?.move(dx: dx, dy: dy)
    }

    // MARK: - Life cycle

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            discovery.start()
            if currentBox != nil, pairing == nil {
                switch connectionState {
                case .idle, .failed: reconnect()
                default: break
                }
            }
            if showMouse || showController, let box = currentBox {
                connectReceiver(box)
                if showMouse, gyroWanted { setGyro(true) }
            }
        case .background:
            gyro.stop()
            receiver?.close()
            receiver = nil
            mouseState = .off
            session?.disconnect()
            session = nil
            if pairing == nil {
                connectionState = .idle
            }
            discovery.stop()
        default:
            break
        }
    }

    // MARK: - Private

    private func restoreLastBox() {
        guard let id = store.lastBoxId,
              store.box(id)?.fingerprint != nil,
              let box = boxes.first(where: { $0.id == id })
        else { return }
        connect(box)
    }

    private func teardown() {
        pairing?.cancel()
        pairing = nil
        session?.disconnect()
        session = nil
        receiver?.close()
        receiver = nil
        gyro.stop()
        mouseState = .off
        volume = nil
        boxIsOn = nil
        connectionState = .idle
    }

    private func clientIdentity() -> SecIdentity? {
        do {
            return try CertificateManager.shared.identity()
        } catch {
            alertMessage = error.localizedDescription
            return nil
        }
    }

    private func rebuildBoxes() {
        var result: [String: TVBox] = [:]
        for saved in store.all {
            result[saved.id] = TVBox(
                id: saved.id,
                name: saved.name,
                host: saved.host,
                port: saved.port,
                isPaired: saved.fingerprint != nil,
                isDiscovered: false,
                isManual: saved.isManual,
                receiverHost: nil,
                receiverPort: nil
            )
        }
        for service in discovery.tvBoxes {
            let id = service.name
            var box = result[id] ?? TVBox(
                id: id,
                name: service.name,
                host: nil,
                port: AtvRemoteSession.defaultPort,
                isPaired: false,
                isDiscovered: true,
                isManual: false,
                receiverHost: nil,
                receiverPort: nil
            )
            box.isDiscovered = true
            if let host = service.host {
                box.host = host
                if let port = service.port { box.port = port }
                let port = box.port
                if let saved = store.box(id), saved.host != host || saved.port != port {
                    store.update(id) { record in
                        record.host = host
                        record.port = port
                    }
                }
            }
            result[id] = box
        }
        let receivers = discovery.receivers
        for (id, box) in result {
            guard let host = box.host, let receiver = receivers.first(where: { $0.host == host }) else { continue }
            var updated = box
            updated.receiverHost = receiver.host
            updated.receiverPort = receiver.port
            result[id] = updated
        }
        boxes = TVBox.knownFirst(Array(result.values), recent: store.recentIds)

        guard let current = currentBox, let updated = result[current.id] else { return }
        let hostChanged = updated.host != current.host
        currentBox = updated
        if hostChanged, pairing == nil, case .failed = connectionState {
            reconnect()
        }
    }
}

/// The change from the text that the box has to the new text of the phone field: how many characters to delete
/// at the end, and what to insert after that.
struct TextChange: Equatable {
    let deletes: Int
    let insert: String

    init(from old: String, to new: String) {
        let a = Array(old), b = Array(new)
        var common = 0
        while common < a.count, common < b.count, a[common] == b[common] { common += 1 }
        deletes = a.count - common
        insert = String(b[common...])
    }
}

/// The state of "Type link on the box".
enum LinkTyping {
    case idle, waiting, sending, typed, noKeyboard
}
