import AVFoundation
import Combine
import Foundation
import UIKit

/// The app's state and the only place UI actions turn into work.
///
/// Views read published state and call methods; they never touch the audio
/// engine, the file system or the DSP core directly. That keeps every action
/// reachable without the visual interface (VoiceOver, keyboard, tests) and lets
/// any engine be replaced behind it.
@MainActor
final class AppModel: ObservableObject {
    enum Activity: Equatable {
        case idle
        case importing(fraction: Double)
        case recording
        case finishingRecording
    }

    // MARK: Published state

    @Published private(set) var document: ProjectDocument?
    @Published private(set) var analysis: AudioAnalysis?
    @Published private(set) var projects: [ProjectSummary] = []
    @Published private(set) var activity: Activity = .idle
    @Published private(set) var playbackState: AudioEngine.State = .idle
    @Published private(set) var position: Double = 0
    @Published private(set) var inputLevel = AudioEngine.InputLevel(peakDbfs: -160, clipped: false)
    @Published private(set) var recordingSeconds: Double = 0
    @Published private(set) var canUndo = false
    @Published private(set) var canRedo = false
    @Published var error: AppError?
    /// The microphone's pitch while recording or while the tuner is open.
    @Published private(set) var livePitch: LivePitch?
    @Published private(set) var tunerActive = false
    /// Whether the tuner speaks each newly held note (useful with VoiceOver).
    @Published var tunerSpeaksNotes: Bool = UserDefaults.standard.bool(forKey: AppModel.tunerSpeechKey) {
        didSet { UserDefaults.standard.set(tunerSpeaksNotes, forKey: Self.tunerSpeechKey) }
    }
    /// Set when the last session ended without closing this project.
    @Published var recoveryNotice: String?

    let l10n: L10n
    let announcer = Announcer()
    /// The user's own maqamat and tuning tables, shared by all projects.
    let library: MaqamLibrary

    private let engine: AudioEngine
    private let session = AudioSessionController()
    private var store: ProjectStore?
    private var history = UndoHistory<ProjectDocument.Editable>()
    private var saveTask: Task<Void, Never>?
    private var importTask: Task<Void, Never>?
    private var positionTimer: Timer?
    private var recordingStartedAt: Date?
    private var observers: [NSObjectProtocol] = []
    private var heldStep: (step: Int, octave: Int, since: Date)?
    private var announcedStep: (step: Int, octave: Int)?

    static let lastProjectKey = "maqamstudio.lastProject"
    static let tunerSpeechKey = "maqamstudio.tuner.speak"
    /// How long after an edit the project is written to disk.
    static let autosaveDelay: Duration = .milliseconds(800)

    init(l10n: L10n, engine: AudioEngine = AudioEngine(), storeRoot: URL? = nil, libraryURL: URL? = nil) {
        self.l10n = l10n
        self.engine = engine
        let fallbackLibrary = FileManager.default.temporaryDirectory.appendingPathComponent("maqam-library.json")
        self.library = MaqamLibrary(fileURL: libraryURL ?? (try? MaqamLibrary.defaultURL()) ?? fallbackLibrary)
        do {
            store = try ProjectStore(root: storeRoot ?? ProjectStore.defaultRoot())
        } catch {
            self.error = .projectSaveFailed(detail: (error as NSError).localizedDescription)
        }
        engine.onPlaybackFinished = { [weak self] in self?.playbackFinished() }
        engine.onInputLevel = { [weak self] level in self?.inputLevel = level }
        engine.onLivePitch = { [weak self] pitch in self?.receivedLivePitch(pitch) }
        observeSystemEvents()
    }

    /// Runs once at launch: audio session, project list, crash recovery.
    func start() {
        do {
            try session.configure()
        } catch let failure as AppError {
            error = failure
        } catch {}
        refreshProjects()
        if let crashed = projects.first(where: { $0.needsRecovery }) {
            openProject(id: crashed.id)
            recoveryNotice = l10n("recovery.message", crashed.name)
            announcer.announce(recoveryNotice ?? "", important: true)
        } else if let last = UserDefaults.standard.string(forKey: Self.lastProjectKey).flatMap(UUID.init(uuidString:)),
                  projects.contains(where: { $0.id == last }) {
            openProject(id: last)
        }
    }

    // MARK: Projects

    var hasAudio: Bool { document?.original != nil }

    func refreshProjects() {
        projects = store?.list() ?? []
    }

    func newProject() {
        guard let store else { return }
        closeCurrentProject()
        do {
            let document = try store.create(name: nextProjectName())
            adopt(document)
            announcer.announce(l10n("announce.projectcreated", document.name))
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
        }
        refreshProjects()
    }

    func openProject(id: UUID) {
        guard let store else { return }
        if document?.id == id { return }
        closeCurrentProject()
        do {
            let (opened, restored) = try store.open(id: id)
            adopt(opened)
            if restored { recoveryNotice = l10n("recovery.backup", opened.name) }
            if opened.original != nil, !store.verifyOriginal(of: opened) {
                error = .corruptedAudio
            }
            announcer.announce(l10n("announce.projectopened", opened.name))
        } catch {
            present(error) { _ in .projectCorrupted(name: id.uuidString) }
        }
        refreshProjects()
    }

    func duplicateCurrentProject() {
        guard let current = document else { return }
        duplicateProject(id: current.id)
    }

    /// Copies a project, original audio included, and opens the copy.
    func duplicateProject(id: UUID) {
        guard let store else { return }
        let name = document?.id == id ? document?.name : projects.first { $0.id == id }?.name
        guard let name else { return }
        if document?.id == id { saveNow() }
        do {
            let copy = try store.duplicate(id: id, newName: l10n("project.copyname", name))
            refreshProjects()
            openProject(id: copy.id)
            announcer.announce(l10n("announce.duplicated", copy.name))
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
        }
    }

    func deleteProject(id: UUID) {
        guard let store else { return }
        if document?.id == id { closeCurrentProject(); UserDefaults.standard.removeObject(forKey: Self.lastProjectKey) }
        do {
            try store.delete(id: id)
            announcer.announce(l10n("announce.deleted"))
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
        }
        refreshProjects()
    }

    func rename(to newName: String) {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != document?.name else { return }
        edit(actionKey: "action.rename") { $0.name = trimmed }
    }

    func closeCurrentProject() {
        guard let current = document else { return }
        stopTuner()
        importTask?.cancel()
        if activity == .recording { stopRecording() }
        engine.unload()
        stopPositionTimer()
        saveNow()
        store?.unlock(id: current.id)
        document = nil
        analysis = nil
        history.clear()
        updateUndoState()
        playbackState = .idle
        position = 0
    }

    /// Called when the app moves to the background: everything is written now.
    func flush() {
        saveNow()
    }

    private func enteredBackground() {
        flush()
        // The tuner listens only while it is on screen.
        stopTuner()
        // Recording and playback keep running in the background (audio
        // background mode), so their project stays open and locked.
        if activity == .recording || playbackState == .playing { return }
        if let current = document { store?.unlock(id: current.id) }
    }

    private func enteringForeground() {
        if let current = document { store?.lock(id: current.id) }
    }

    private func adopt(_ opened: ProjectDocument) {
        document = opened
        history.clear()
        updateUndoState()
        store?.lock(id: opened.id)
        UserDefaults.standard.set(opened.id.uuidString, forKey: Self.lastProjectKey)
        analysis = store?.loadAnalysis(for: opened)
        position = opened.playback.positionSeconds
        if opened.original != nil {
            loadPlayback()
            if analysis == nil { reanalyze() }
        }
    }

    private func nextProjectName() -> String {
        let existing = Set(projects.map(\.name))
        var index = projects.count + 1
        while existing.contains(l10n("project.defaultname", l10n.number(Double(index)))) { index += 1 }
        return l10n("project.defaultname", l10n.number(Double(index)))
    }

    // MARK: Editing with undo

    /// Applies an edit to the editable part of the document, records it for
    /// undo, and schedules an autosave.
    func edit(actionKey: String, _ change: (inout ProjectDocument.Editable) -> Void) {
        guard var current = document else { return }
        let before = current.editable
        var after = before
        change(&after)
        guard after != before else { return }
        history.record(before, actionKey: actionKey)
        current.editable = after
        current.modifiedAt = Date()
        document = current
        updateUndoState()
        scheduleSave()
    }

    func undo() {
        guard var current = document, let entry = history.undo(current: current.editable) else { return }
        current.editable = entry.value
        current.modifiedAt = Date()
        document = current
        updateUndoState()
        scheduleSave()
        announcer.announce(l10n("announce.undone", l10n(entry.actionKey)))
    }

    func redo() {
        guard var current = document, let entry = history.redo(current: current.editable) else { return }
        current.editable = entry.value
        current.modifiedAt = Date()
        document = current
        updateUndoState()
        scheduleSave()
        announcer.announce(l10n("announce.redone", l10n(entry.actionKey)))
    }

    var undoLabel: String {
        history.undoActionKey.map { l10n("action.undo.named", l10n($0)) } ?? l10n("action.undo")
    }

    var redoLabel: String {
        history.redoActionKey.map { l10n("action.redo.named", l10n($0)) } ?? l10n("action.redo")
    }

    private func updateUndoState() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    // MARK: Saving

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: Self.autosaveDelay)
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    func saveNow() {
        saveTask?.cancel()
        guard let store, var current = document else { return }
        current.playback.positionSeconds = position
        do {
            try store.save(current)
            document = current
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
        }
    }

    // MARK: Import

    func importAudio(from url: URL) {
        guard activity == .idle else { return }
        let accessing = url.startAccessingSecurityScopedResource()
        let metadata: AudioMetadata
        do {
            metadata = try AudioFileReader.inspect(url)
        } catch {
            if accessing { url.stopAccessingSecurityScopedResource() }
            present(error) { _ in .corruptedAudio }
            return
        }
        prepareProjectForNewAudio(named: (url.lastPathComponent as NSString).deletingPathExtension)
        guard let store, var current = document else {
            if accessing { url.stopAccessingSecurityScopedResource() }
            return
        }
        do {
            try store.importOriginal(from: url, into: &current, metadata: metadata, recorded: false)
            if accessing { url.stopAccessingSecurityScopedResource() }
            current.modifiedAt = Date()
            document = current
            try store.save(current)
        } catch {
            if accessing { url.stopAccessingSecurityScopedResource() }
            present(error) { .projectSaveFailed(detail: $0) }
            return
        }
        announcer.announce(l10n("announce.imported", metadata.sourceName, l10n.spokenDuration(metadata.duration)))
        loadPlayback()
        reanalyze()
        refreshProjects()
    }

    /// A project holds one original. New audio goes into the current project if
    /// it is still empty, otherwise into a fresh project, so nothing is replaced.
    private func prepareProjectForNewAudio(named name: String) {
        if document == nil || document?.original != nil {
            newProject()
            if !name.isEmpty { renameWithoutUndo(to: name) }
        }
    }

    private func renameWithoutUndo(to name: String) {
        guard var current = document else { return }
        current.name = name
        document = current
        saveNow()
    }

    /// Analyses the original in the background, with spoken progress.
    func reanalyze() {
        guard let store, let current = document, let original = current.original,
              let url = store.originalURL(for: current) else { return }
        importTask?.cancel()
        activity = .importing(fraction: 0)
        announcer.reset(task: "analysis")
        announcer.announce(l10n("announce.analyzing"))
        let projectId = current.id
        let reportProgress: @Sendable (Double) -> Void = { [weak self] fraction in
            Task { @MainActor in self?.analysisProgress(fraction) }
        }
        importTask = Task { [weak self] in
            let result = await Task.detached(priority: .userInitiated) { () -> Result<AudioFileReader.FullAnalysis, Error> in
                Result { try AudioFileReader.analyzeFully(url, progress: reportProgress) }
            }.value
            guard let self, self.document?.id == projectId else { return }
            self.activity = .idle
            switch result {
            case .success(let full):
                let analysis = AudioAnalysis(levels: full.levels, waveform: full.waveform, analyzedAt: Date(),
                                             sourceSHA256: original.sha256, pitch: full.pitch)
                self.analysis = analysis
                try? store.saveAnalysis(analysis, for: projectId)
                self.announcer.announce(self.l10n("announce.analyzed"), important: true)
            case .failure(let failure):
                if failure is CancellationError { return }
                self.present(failure) { _ in .corruptedAudio }
            }
        }
    }

    private func analysisProgress(_ fraction: Double) {
        guard case .importing = activity else { return }
        activity = .importing(fraction: fraction)
        announcer.progress(task: "analysis", fraction: fraction) { [l10n] reached in
            l10n("announce.progress", l10n.percent(reached))
        }
    }

    // MARK: Recording

    func startRecording() {
        guard activity == .idle else { return }
        Task { await beginRecording() }
    }

    /// Asks for the microphone if needed; reports why it cannot be used if not.
    private func microphoneReady() async -> Bool {
        switch session.recordPermission {
        case .denied:
            error = .microphonePermissionDenied
            return false
        case .undetermined:
            guard await session.requestRecordPermission() else {
                error = .microphonePermissionDenied
                return false
            }
        case .granted:
            break
        }
        guard session.hasInput else { error = .noInputDevice; return false }
        return true
    }

    private func beginRecording() async {
        guard await microphoneReady() else { return }
        stopTuner()
        do {
            try store?.ensureSpace(forBytes: 300 * 1024 * 1024)
            try session.configure()
        } catch {
            present(error) { .recordingFailed(detail: $0) }
            return
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("take-\(UUID().uuidString).caf")
        do {
            stopPlayback()
            try engine.startRecording(to: url)
            activity = .recording
            recordingStartedAt = Date()
            recordingSeconds = 0
            startPositionTimer()
            announcer.announce(l10n("announce.recording"), important: true)
        } catch {
            try? FileManager.default.removeItem(at: url)
            present(error) { .recordingFailed(detail: $0) }
        }
    }

    func stopRecording() {
        guard activity == .recording else { return }
        activity = .finishingRecording
        livePitch = nil
        stopPositionTimer()
        let take: (url: URL, frames: AVAudioFramePosition)
        do {
            take = try engine.stopRecording()
        } catch {
            activity = .idle
            present(error) { .recordingFailed(detail: $0) }
            return
        }
        activity = .idle
        guard take.frames > 0 else {
            try? FileManager.default.removeItem(at: take.url)
            error = .emptyAudio
            return
        }
        let metadata: AudioMetadata
        do {
            metadata = try AudioFileReader.inspect(take.url)
        } catch {
            present(error) { _ in .corruptedAudio }
            return
        }
        prepareProjectForNewAudio(named: "")
        guard let store, var current = document else { return }
        do {
            try store.importOriginal(from: take.url, into: &current, metadata: metadata, recorded: true)
            current.modifiedAt = Date()
            document = current
            try store.save(current)
        } catch {
            present(error) { .recordingFailed(detail: $0) }
            return
        }
        announcer.announce(l10n("announce.recorded", l10n.spokenDuration(metadata.duration)), important: true)
        loadPlayback()
        reanalyze()
        refreshProjects()
    }

    // MARK: Playback

    var duration: Double { document?.original?.duration ?? 0 }

    private func loadPlayback() {
        guard let store, let current = document, let url = store.originalURL(for: current) else { return }
        do {
            try engine.load(url: url)
            try engine.seek(to: position)
        } catch {
            present(error) { .playbackFailed(detail: $0) }
        }
    }

    func togglePlayback() {
        if engine.state == .playing { pause() } else { play() }
    }

    func play() {
        guard hasAudio, activity == .idle else { return }
        stopTuner()
        do {
            if position >= duration - 0.05 { try engine.seek(to: 0) }
            try engine.play()
            playbackState = engine.state
            startPositionTimer()
        } catch {
            present(error) { .playbackFailed(detail: $0) }
        }
    }

    func pause() {
        engine.pause()
        playbackState = engine.state
        position = engine.position
        stopPositionTimer()
        scheduleSave()
    }

    func stopPlayback() {
        engine.stopPlayback()
        playbackState = engine.state
        position = 0
        stopPositionTimer()
    }

    func seek(to seconds: Double) {
        let clamped = min(max(0, seconds), duration)
        do {
            try engine.seek(to: clamped)
            position = clamped
            playbackState = engine.state
            scheduleSave()
        } catch {
            present(error) { .playbackFailed(detail: $0) }
        }
    }

    func skip(by seconds: Double) {
        seek(to: (engine.state == .playing ? engine.position : position) + seconds)
    }

    private func playbackFinished() {
        playbackState = engine.state
        position = 0
        stopPositionTimer()
        announcer.announce(l10n("announce.playbackfinished"))
    }

    private func startPositionTimer() {
        stopPositionTimer()
        positionTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.tick() }
        }
    }

    private func stopPositionTimer() {
        positionTimer?.invalidate()
        positionTimer = nil
    }

    private func tick() {
        if activity == .recording, let started = recordingStartedAt {
            recordingSeconds = Date().timeIntervalSince(started)
        } else if engine.state == .playing {
            position = engine.position
        }
    }

    /// Plays from the start of a note, as from the notes list.
    func play(from seconds: Double) {
        seek(to: seconds)
        if engine.state != .playing { play() }
    }

    // MARK: Pitch and intonation

    var pitch: PitchAnalysis? { analysis?.pitch }

    /// The maqam as the user has tuned it (built-in degrees plus their offsets).
    var effectiveMaqam: MaqamDefinition? {
        guard let maqam = currentMaqam else { return nil }
        return maqam.applying(offsets: document?.maqam.degreeOffsets ?? [:])
    }

    /// The notes judged against the chosen maqam and tonic; nil until both are chosen.
    var intonation: Intonation? {
        guard let notes = pitch?.notes, let maqam = effectiveMaqam, let tonic = document?.maqam.tonicHz else { return nil }
        return Intonation.evaluate(notes, maqam: maqam, tonicHz: tonic)
    }

    // MARK: Live tuner

    func startTuner() {
        guard activity == .idle, !tunerActive else { return }
        Task {
            guard await microphoneReady() else { return }
            do {
                stopPlayback()
                try session.configure()
                try engine.startMonitoring()
                tunerActive = true
                heldStep = nil
                announcedStep = nil
                announcer.announce(l10n("announce.tunerstarted"))
            } catch {
                present(error) { .audioEngineFailed(detail: $0) }
            }
        }
    }

    func stopTuner() {
        guard tunerActive else { return }
        engine.stopMonitoring()
        tunerActive = false
        livePitch = nil
    }

    private func receivedLivePitch(_ pitch: LivePitch) {
        guard tunerActive || activity == .recording else { return }
        livePitch = pitch
        guard tunerActive, tunerSpeaksNotes else { return }
        guard pitch.voiced else { heldStep = nil; return }
        let named = PitchNaming.name(hz: pitch.hz, a4Hz: document?.maqam.a4Hz ?? 440)
        let now = Date()
        if let held = heldStep, held.step == named.step, held.octave == named.octave {
            // Spoken once the note has been held half a second, and only when it changes.
            if now.timeIntervalSince(held.since) >= 0.5,
               announcedStep.map({ $0.step != named.step || $0.octave != named.octave }) ?? true {
                announcedStep = (named.step, named.octave)
                announcer.announce(TunerText.spoken(pitch, model: self, l10n: l10n))
            }
        } else {
            heldStep = (named.step, named.octave, now)
        }
    }

    // MARK: Maqam (manual choice; detection arrives with phase 5)

    func chooseMaqam(id: String?) {
        let definition = id.flatMap(library.definition(id:))
        edit(actionKey: "action.maqam") { editable in
            editable.maqam.maqamId = id
            editable.maqam.manuallyChosen = id != nil
            editable.maqam.degreeOffsets = [:]
            editable.maqam.customDefinition = definition?.isCustom == true ? definition : nil
            if editable.maqam.tonicHz == nil, let definition {
                editable.maqam.tonicHz = definition.typicalTonicHz
            }
        }
    }

    func chooseTonic(hz: Double) {
        edit(actionKey: "action.tonic") { $0.maqam.tonicHz = hz }
    }

    /// The chosen maqam as defined (offsets not applied). A custom maqam comes
    /// from the library when it is still there, else from the project's copy.
    var currentMaqam: MaqamDefinition? {
        guard let settings = document?.maqam, let id = settings.maqamId else { return nil }
        if let found = library.definition(id: id) { return found }
        return settings.customDefinition?.id == id ? settings.customDefinition : nil
    }

    // MARK: Tuning the degrees (phase 3)

    /// Range offered for adjusting one degree, either side of its definition.
    static let degreeOffsetRange: ClosedRange<Double> = -100...100

    func setDegreeOffset(_ index: Int, to cents: Double) {
        let clamped = min(max(cents, Self.degreeOffsetRange.lowerBound), Self.degreeOffsetRange.upperBound)
        edit(actionKey: "action.degreeoffset") { editable in
            editable.maqam.degreeOffsets[index] = clamped == 0 ? nil : clamped
        }
    }

    func resetDegreeOffsets() {
        edit(actionKey: "action.resetoffsets") { $0.maqam.degreeOffsets = [:] }
    }

    func applyTable(_ table: TuningTable) {
        guard table.maqamId == document?.maqam.maqamId else { return }
        edit(actionKey: "action.applytable") { $0.maqam.degreeOffsets = table.offsets }
        announcer.announce(l10n("announce.tableapplied", table.name))
    }

    /// Saves this project's degree adjustments as a reusable tuning table.
    func saveOffsetsAsTable(named name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let settings = document?.maqam, let id = settings.maqamId else { return }
        do {
            try library.saveTable(TuningTable(id: UUID(), name: trimmed, maqamId: id, offsets: settings.degreeOffsets))
            announcer.announce(l10n("announce.tablesaved", trimmed))
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
        }
    }

    func deleteTable(_ table: TuningTable) {
        do { try library.deleteTable(id: table.id) } catch { present(error) { .projectSaveFailed(detail: $0) } }
    }

    /// Reference pitch for note names, 415–466 Hz (Baroque to bright modern).
    static let a4Range: ClosedRange<Double> = 415...466

    func setA4(_ hz: Double) {
        let clamped = min(max(hz, Self.a4Range.lowerBound), Self.a4Range.upperBound)
        edit(actionKey: "action.a4") { $0.maqam.a4Hz = clamped }
    }

    // MARK: The user's own maqamat (phase 3)

    /// Saves a custom maqam to the library; a project using it gets the new copy.
    @discardableResult
    func saveCustomMaqam(_ maqam: MaqamDefinition) -> Bool {
        do {
            try library.save(maqam)
        } catch {
            self.error = .maqamInvalid(name: maqam.name(l10n))
            return false
        }
        if document?.maqam.maqamId == maqam.id {
            edit(actionKey: "action.editmaqam") { $0.maqam.customDefinition = maqam }
        }
        objectWillChange.send()
        announcer.announce(l10n("announce.maqamsaved", maqam.name(l10n)))
        return true
    }

    func deleteCustomMaqam(id: String) {
        do {
            try library.delete(maqamId: id)
            objectWillChange.send()
            announcer.announce(l10n("announce.maqamdeleted"))
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
        }
    }

    /// Writes the library to a temporary file for the share sheet.
    func exportLibraryFile() -> URL? {
        do {
            let data = try library.exportData()
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(l10n("library.filename") + ".json")
            try data.write(to: url, options: [.atomic])
            return url
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
            return nil
        }
    }

    func importLibrary(from url: URL) {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            let added = try library.importData(data)
            objectWillChange.send()
            announcer.announce(l10n("announce.imported.library", l10n.number(Double(added.maqamat)),
                                    l10n.number(Double(added.tables))), important: true)
        } catch MaqamLibrary.ImportError.invalidMaqam(let name) {
            self.error = .maqamInvalid(name: name)
        } catch MaqamLibrary.ImportError.newerVersion {
            self.error = .libraryTooNew
        } catch {
            self.error = .libraryUnreadable
        }
    }

    // MARK: System events and errors

    private func observeSystemEvents() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            Task { @MainActor in self?.interrupted() }
        })
        // iOS ends suspended apps without warning, so "closed cleanly" has to
        // mean "left the foreground": everything is saved and the lock removed
        // then. A lock found at launch therefore means a crash while in use.
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.enteredBackground() }
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.enteringForeground() }
        })
    }

    private func interrupted() {
        if activity == .recording { stopRecording() }
        if tunerActive { tunerActive = false; livePitch = nil }
        engine.interrupt()
        playbackState = engine.state
        stopPositionTimer()
        announcer.announce(l10n("announce.interrupted"), important: true)
    }

    private func present(_ error: Error, fallback: (String) -> AppError) {
        let appError = AppError.from(error, fallback: fallback)
        self.error = appError
        announcer.announce(l10n(appError.titleKey), important: true)
    }
}
