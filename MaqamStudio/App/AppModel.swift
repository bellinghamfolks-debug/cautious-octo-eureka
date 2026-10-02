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
        case rendering(fraction: Double)
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
    /// The last tuning render of this project, if any.
    @Published private(set) var tuningRender: TuningRenderInfo?
    /// Which version playback uses.
    enum ListeningSource: String, CaseIterable, Identifiable {
        case original, tuned, studio, vocals, accompaniment
        var id: String { rawValue }
    }
    @Published private(set) var listening: ListeningSource = .original
    var listeningToTuned: Bool { listening == .tuned }
    /// The last Auto Studio render of this project, if any.
    @Published private(set) var studioRender: StudioRenderInfo?
    /// What the singing suggests; recomputed when the notes or the library change.
    @Published private(set) var detection: MaqamDetectionResult?
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
        refreshSeparationModels()
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
        tuningRender = nil
        studioRender = nil
        exportResult = nil
        separation = nil
        listening = .original
        detection = nil
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
        tuningRender = store?.loadRenderInfo(for: opened)
        studioRender = store?.loadStudioInfo(for: opened)
        separation = store?.loadSeparationInfo(for: opened)
        listening = .original
        updateDetection()
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
        documentChanged()
    }

    func undo() {
        guard var current = document, let entry = history.undo(current: current.editable) else { return }
        current.editable = entry.value
        current.modifiedAt = Date()
        document = current
        updateUndoState()
        scheduleSave()
        documentChanged()
        announcer.announce(l10n("announce.undone", l10n(entry.actionKey)))
    }

    func redo() {
        guard var current = document, let entry = history.redo(current: current.editable) else { return }
        current.editable = entry.value
        current.modifiedAt = Date()
        document = current
        updateUndoState()
        scheduleSave()
        documentChanged()
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

    func importAudio(from url: URL, name: String? = nil) {
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
        prepareProjectForNewAudio(named: name ?? (url.lastPathComponent as NSString).deletingPathExtension)
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
                self.updateDetection()
                self.applyDetectionIfUnchosen()
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
        guard let store, let current = document, var url = store.originalURL(for: current) else { return }
        switch listening {
        case .tuned where tuningIsFresh:
            if let render = tuningRender { url = store.renderURL(for: current.id, fileName: render.fileName) }
        case .studio where studioIsFresh:
            if let render = studioRender { url = store.renderURL(for: current.id, fileName: render.fileName) }
        case .vocals where separationIsFresh:
            url = store.renderURL(for: current.id, fileName: SeparationInfo.vocalsFile)
        case .accompaniment where separationIsFresh:
            url = store.renderURL(for: current.id, fileName: SeparationInfo.accompanimentFile)
        default:
            break
        }
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

    // MARK: Pitch correction (phase 4)

    /// The project's tuning settings, or the Natural preset before the first change.
    var tuningSettings: PitchCorrectionSettings {
        document?.processing.tuning ?? .preset(.natural)
    }

    /// What the tuned audio would depend on now; nil until maqam and tonic are chosen.
    var currentTuningFingerprint: String? {
        guard let maqam = effectiveMaqam, let tonic = document?.maqam.tonicHz,
              let sha = document?.original?.sha256 else { return nil }
        return TuningRenderer.fingerprint(settings: tuningSettings, maqam: maqam, tonicHz: tonic, sourceSHA256: sha)
    }

    /// Whether the tuned file matches the current settings, maqam and tonic.
    var tuningIsFresh: Bool {
        guard let render = tuningRender, let current = currentTuningFingerprint else { return false }
        return render.fingerprint == current
    }

    var canTune: Bool {
        hasAudio && !(pitch?.notes.isEmpty ?? true) && effectiveMaqam != nil && document?.maqam.tonicHz != nil
    }

    func chooseTuningPreset(_ preset: PitchCorrectionSettings.Preset) {
        updateTuning(actionKey: "action.tuningpreset") { $0 = $0.applying(preset) }
    }

    func setTuning<Value>(_ keyPath: WritableKeyPath<PitchCorrectionSettings, Value>, to value: Value) {
        updateTuning(actionKey: "action.tuningsetting") { settings in
            settings[keyPath: keyPath] = value
            settings.preset = .custom
        }
    }

    /// Pins a note, leaves it alone, or (nil) returns it to automatic tuning.
    func setNoteOverride(_ noteId: Int, _ override: PitchCorrectionSettings.NoteOverride?) {
        updateTuning(actionKey: "action.noteoverride") { $0.noteOverrides[noteId] = override }
    }

    private func updateTuning(actionKey: String, _ change: (inout PitchCorrectionSettings) -> Void) {
        let current = tuningSettings
        edit(actionKey: actionKey) { editable in
            var settings = editable.processing.tuning ?? current
            change(&settings)
            editable.processing.tuning = settings
        }
    }

    /// After any edit: a tuned render that no longer matches is not played.
    private func documentChanged() {
        let stale = !canListen(to: listening)
        if stale {
            listening = .original
            reloadKeepingPosition()
            announcer.announce(l10n("announce.tunedstale"))
        }
    }

    /// Renders the tuned version in the background, then measures it.
    func renderTuning() {
        guard activity == .idle, canTune else { return }
        importTask = Task { [weak self] in
            guard let self else { return }
            self.beginRendering(task: "tuning", announcementKey: "announce.tuning")
            if await self.performTuning(progressFrom: 0, to: 1) {
                self.listening = .tuned
                self.reloadKeepingPosition()
                self.announcer.announce(self.tuningResultText, important: true)
            }
            self.activity = .idle
        }
    }

    private var progressBase = 0.0
    private var progressSpan = 1.0
    /// "tuning", "studio" or "export": what progress and cancellation announce.
    private var renderTask = "tuning"

    private func beginRendering(task: String, announcementKey: String) {
        renderTask = task
        stopTuner()
        if listening != .original {
            listening = .original
            reloadKeepingPosition()
        }
        activity = .rendering(fraction: 0)
        announcer.reset(task: task)
        announcer.announce(l10n(announcementKey))
    }

    /// The tuning render itself; true when a fresh tuned file is in place.
    private func performTuning(progressFrom base: Double, to end: Double) async -> Bool {
        guard canTune, let store, let current = document, let original = current.original,
              let source = store.originalURL(for: current), let pitch, let maqam = effectiveMaqam,
              let tonic = current.maqam.tonicHz, let fingerprint = currentTuningFingerprint else { return false }
        let settings = tuningSettings
        do {
            try store.prepareRendersFolder(for: current.id)
            try store.ensureSpace(forBytes: Int64(original.frames) * Int64(max(1, original.channels)) * 4)
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
            return false
        }
        progressBase = base
        progressSpan = end - base
        let projectId = current.id
        let temporary = store.renderURL(for: projectId, fileName: "rendering-\(UUID().uuidString).caf")
        let destination = store.renderURL(for: projectId, fileName: TuningRenderer.fileName)
        let reportProgress: @Sendable (Double) -> Void = { [weak self] fraction in
            Task { @MainActor in self?.renderProgress(fraction) }
        }
        // A detached task does not inherit cancellation; pass it on explicitly,
        // so Cancel really stops the work and its result is never applied.
        let job = Task.detached(priority: .userInitiated) { () -> Result<TuningRenderInfo.Measured?, Error> in
            Result {
                try TuningRenderer.render(source: source, destination: temporary, pitch: pitch, maqam: maqam,
                                          tonicHz: tonic, settings: settings, progress: reportProgress)
                // Measure the result itself rather than assume the correction worked.
                let check = try AudioFileReader.analyzeFully(temporary, buckets: 64) { reportProgress(0.9 + 0.1 * $0) }
                guard let notes = check.pitch?.notes,
                      let measured = Intonation.evaluate(notes, maqam: maqam, tonicHz: tonic) else { return nil }
                return TuningRenderInfo.Measured(noteCount: notes.count, inTuneFraction: measured.inTuneFraction,
                                                 meanAbsoluteDeviationCents: measured.meanAbsoluteDeviationCents)
            }
        }
        let result = await withTaskCancellationHandler { await job.value } onCancel: { job.cancel() }
        guard !Task.isCancelled, document?.id == projectId else { try? FileManager.default.removeItem(at: temporary); return false }
        switch result {
        case .success(let measured):
            do {
                try Self.replace(destination, with: temporary)
                let info = TuningRenderInfo(fileName: TuningRenderer.fileName, sourceSHA256: original.sha256,
                                            fingerprint: fingerprint, renderedAt: Date(), after: measured)
                try store.saveRenderInfo(info, for: projectId)
                tuningRender = info
                return true
            } catch {
                present(error) { .projectSaveFailed(detail: $0) }
                return false
            }
        case .failure(let failure):
            try? FileManager.default.removeItem(at: temporary)
            if !(failure is CancellationError) { present(failure) { .projectSaveFailed(detail: $0) } }
            return false
        }
    }

    private static func replace(_ destination: URL, with temporary: URL) throws {
        if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: destination)
        }
    }

    /// "Accuracy before 62%, after 97%", for the screen and VoiceOver.
    var tuningResultText: String {
        guard let after = tuningRender?.after else { return l10n("tuning.done") }
        let before = intonation.map { l10n.percent($0.inTuneFraction) } ?? "–"
        return l10n("tuning.result", before, l10n.percent(after.inTuneFraction),
                    l10n.number(after.meanAbsoluteDeviationCents.rounded()))
    }

    private func renderProgress(_ fraction: Double) {
        guard case .rendering = activity else { return }
        activity = .rendering(fraction: progressBase + progressSpan * min(1, max(0, fraction)))
        announcer.progress(task: renderTask, fraction: fraction) { [l10n, renderTask] reached in
            l10n("announce.\(renderTask)progress", l10n.percent(reached))
        }
    }

    func cancelRendering() {
        guard case .rendering = activity else { return }
        importTask?.cancel()
        activity = .idle
        announcer.announce(l10n("announce.\(renderTask)cancelled"))
    }

    /// Switches between the original and the tuned render at the same moment.
    func setListening(tuned: Bool) {
        setListening(tuned ? .tuned : .original)
    }

    /// Switches version at the same moment of the song, without stopping.
    func setListening(_ source: ListeningSource) {
        guard source != listening, canListen(to: source) else { return }
        listening = source
        reloadKeepingPosition()
        announcer.announce(l10n("announce.listening." + source.rawValue))
    }

    func canListen(to source: ListeningSource) -> Bool {
        switch source {
        case .original: return hasAudio
        case .tuned: return tuningIsFresh
        case .studio: return studioIsFresh
        case .vocals, .accompaniment: return separationIsFresh
        }
    }

    private func reloadKeepingPosition() {
        let wasPlaying = engine.state == .playing
        let at = wasPlaying ? engine.position : position
        loadPlayback()
        position = at
        try? engine.seek(to: at)
        if wasPlaying {
            try? engine.play()
            playbackState = engine.state
        }
    }

    // MARK: Auto Studio (phase 6)

    var studioSettings: StudioSettings { document?.processing.studio ?? StudioSettings() }

    /// The audio the studio works on: the tuned voice when wanted and current.
    private var studioInput: (url: URL, fingerprint: String, tuned: Bool)? {
        guard let store, let current = document, let original = current.original,
              let originalURL = store.originalURL(for: current) else { return nil }
        if studioSettings.useTuned, tuningIsFresh, let render = tuningRender {
            return (store.renderURL(for: current.id, fileName: render.fileName), render.fingerprint, true)
        }
        return (originalURL, original.sha256, false)
    }

    var currentStudioFingerprint: String? {
        guard let input = studioInput else { return nil }
        return StudioRenderer.fingerprint(settings: studioSettings, inputFingerprint: input.fingerprint)
    }

    var studioIsFresh: Bool {
        guard let render = studioRender, let current = currentStudioFingerprint else { return false }
        return render.fingerprint == current
    }

    func setStudioProfile(_ profile: GenreProfile) {
        updateStudio(actionKey: "action.studioprofile") { settings in
            settings.profile = profile
            settings.plan = nil  // a new style starts from its own automatic plan
        }
    }

    func setStudioUsesTuned(_ useTuned: Bool) {
        updateStudio(actionKey: "action.studiosetting") { $0.useTuned = useTuned }
    }

    /// A Pro-mode change: from now on this plan is used as edited.
    func setStudioPlan(_ plan: StudioPlanValues) {
        updateStudio(actionKey: "action.studiosetting") { $0.plan = plan }
    }

    func resetStudioToAutomatic() {
        updateStudio(actionKey: "action.studioautomatic") { $0.plan = nil }
    }

    /// The plan Pro mode starts from: the edited one, else the last automatic one.
    var studioPlanForEditing: StudioPlanValues? {
        studioSettings.plan ?? studioRender?.automaticPlan
    }

    private func updateStudio(actionKey: String, _ change: (inout StudioSettings) -> Void) {
        let current = studioSettings
        edit(actionKey: actionKey) { editable in
            var settings = editable.processing.studio ?? current
            change(&settings)
            editable.processing.studio = settings
        }
    }

    /// AUTO STUDIO: tune to the maqam when one is known (in the profile's style
    /// unless tuning was set by hand), then clean, shape and finish the voice.
    func runAutoStudio() {
        guard activity == .idle, hasAudio, pitch != nil else { return }
        importTask = Task { [weak self] in
            guard let self else { return }
            self.beginRendering(task: "studio", announcementKey: "announce.studio")
            let settings = self.studioSettings
            var tuningShare = 0.0
            if settings.useTuned, self.canTune, !self.tuningIsFresh {
                if self.document?.processing.tuning == nil {
                    self.updateTuning(actionKey: "action.tuningpreset") { $0 = $0.applying(settings.profile.tuningPreset) }
                }
                tuningShare = 0.45
                guard await self.performTuning(progressFrom: 0, to: tuningShare) else { self.activity = .idle; return }
            }
            if !self.canTune { self.announcer.announce(self.l10n("announce.studio.notuning")) }
            if await self.performStudio(progressFrom: tuningShare, to: 1) {
                self.listening = .studio
                self.reloadKeepingPosition()
                self.announcer.announce(self.studioResultText, important: true)
            }
            self.activity = .idle
        }
    }

    private func performStudio(progressFrom base: Double, to end: Double) async -> Bool {
        guard let store, let current = document, let original = current.original, let input = studioInput,
              let pitch, let fingerprint = currentStudioFingerprint else { return false }
        let settings = studioSettings
        do {
            try store.prepareRendersFolder(for: current.id)
            // Stereo float, plus a few seconds of tail.
            try store.ensureSpace(forBytes: (Int64(original.frames) + Int64(original.sampleRate * 6)) * 8)
        } catch {
            present(error) { .projectSaveFailed(detail: $0) }
            return false
        }
        progressBase = base
        progressSpan = end - base
        let projectId = current.id
        let temporary = store.renderURL(for: projectId, fileName: "studio-\(UUID().uuidString).caf")
        let destination = store.renderURL(for: projectId, fileName: StudioRenderer.fileName)
        let reportProgress: @Sendable (Double) -> Void = { [weak self] fraction in
            Task { @MainActor in self?.renderProgress(fraction) }
        }
        let track = pitch.track
        let job = Task.detached(priority: .userInitiated) { () -> Result<StudioRenderInfo, Error> in
            Result {
                // 1. Listen to the voice and decide.
                let session = try StudioRenderer.analyse(source: input.url, pitch: track) { reportProgress(0.25 * $0) }
                let automatic = try session.plan(for: settings.profile)
                let plan = settings.plan ?? automatic.plan
                // 2. Measure the chain's loudness, 3. render at the gain that reaches the target.
                let measured = try StudioRenderer.run(session: session, plan: plan, source: input.url, gainDb: 0, limit: false,
                                                      destination: nil) { reportProgress(0.25 + 0.3 * $0) }
                let gain = measured.lufs > -100 ? plan.loudnessTargetLufs - measured.lufs : 0
                let final = try StudioRenderer.run(session: session, plan: plan, source: input.url, gainDb: gain, limit: true,
                                                   destination: temporary) { reportProgress(0.55 + 0.45 * $0) }
                return StudioRenderInfo(fileName: StudioRenderer.fileName, sourceSHA256: original.sha256,
                                        fingerprint: fingerprint, renderedAt: Date(), usedTuned: input.tuned,
                                        before: session.measurements, automaticPlan: automatic.plan, usedPlan: plan,
                                        reasons: automatic.reasons, afterLufs: final.lufs, afterPeakDbfs: final.peakDbfs)
            }
        }
        let result = await withTaskCancellationHandler { await job.value } onCancel: { job.cancel() }
        guard !Task.isCancelled, document?.id == projectId else { try? FileManager.default.removeItem(at: temporary); return false }
        switch result {
        case .success(let info):
            do {
                try Self.replace(destination, with: temporary)
                try store.saveStudioInfo(info, for: projectId)
                studioRender = info
                return true
            } catch {
                present(error) { .projectSaveFailed(detail: $0) }
                return false
            }
        case .failure(let failure):
            try? FileManager.default.removeItem(at: temporary)
            if !(failure is CancellationError) { present(failure) { .projectSaveFailed(detail: $0) } }
            return false
        }
    }

    /// "Loudness −23 → −14 LUFS, peak −1 dBFS", for the screen and VoiceOver.
    var studioResultText: String {
        guard let render = studioRender else { return "" }
        return l10n("studio.result", l10n.number(render.before.integratedLufs, fractionDigits: 1),
                    l10n.number(render.afterLufs, fractionDigits: 1), l10n.number(render.afterPeakDbfs, fractionDigits: 1))
    }

    // MARK: Export (phase 7)

    static let exportOptionsKey = "maqamstudio.export.options"

    /// The last export's choices, kept between exports and launches.
    var exportOptions: ExportOptions {
        get {
            guard let data = UserDefaults.standard.data(forKey: Self.exportOptionsKey),
                  let options = try? JSONDecoder().decode(ExportOptions.self, from: data) else { return ExportOptions() }
            return options.normalized()
        }
        set {
            objectWillChange.send()
            UserDefaults.standard.set(try? JSONEncoder().encode(newValue.normalized()), forKey: Self.exportOptionsKey)
        }
    }

    @Published private(set) var exportResult: ExportResult?

    /// Versions that can be exported now: a render only while it is current.
    var exportSources: [ExportSource] {
        ExportSource.allCases.filter { exportURL(for: $0) != nil }
    }

    /// The most finished current version.
    var preferredExportSource: ExportSource {
        studioIsFresh ? .studio : tuningIsFresh ? .tuned : .original
    }

    private func exportURL(for source: ExportSource) -> URL? {
        guard let store, let current = document else { return nil }
        switch source {
        case .original: return store.originalURL(for: current)
        case .tuned:
            guard tuningIsFresh, let render = tuningRender else { return nil }
            return store.renderURL(for: current.id, fileName: render.fileName)
        case .studio:
            guard studioIsFresh else { return nil }
            return store.renderURL(for: current.id, fileName: StudioRenderer.fileName)
        case .vocals, .accompaniment:
            guard separationIsFresh else { return nil }
            return store.renderURL(for: current.id, fileName: source == .vocals ? SeparationInfo.vocalsFile
                                                                                  : SeparationInfo.accompanimentFile)
        }
    }

    /// Writes the chosen version to Documents/Exports, where the Files app and
    /// the share sheet can reach it. The original stays untouched.
    func export(_ source: ExportSource) {
        guard activity == .idle, let store, let current = document, let original = current.original,
              let input = exportURL(for: source) else { return }
        let options = exportOptions
        importTask = Task { [weak self] in
            guard let self else { return }
            self.beginRendering(task: "export", announcementKey: "announce.export")
            self.exportResult = nil
            let destination: URL
            do {
                let folder = try store.prepareExportsFolder()
                // Uncompressed stereo float at the target rate is the most an export can need.
                let seconds = Double(original.frames) / original.sampleRate
                try store.ensureSpace(forBytes: Int64(seconds * Double(options.sampleRate) * 2 * 4) + 1_000_000)
                destination = folder.appendingPathComponent(AudioExporter.fileName(
                    projectName: current.name, source: source, format: options.format,
                    suffix: self.l10n("export.suffix." + source.rawValue)))
            } catch {
                self.present(error) { .exportFailed(detail: $0) }
                self.activity = .idle
                return
            }
            self.progressBase = 0
            self.progressSpan = 1
            let reportProgress: @Sendable (Double) -> Void = { [weak self] fraction in
                Task { @MainActor in self?.renderProgress(fraction) }
            }
            let job = Task.detached(priority: .userInitiated) { () -> Result<ExportResult, Error> in
                Result {
                    try AudioExporter.export(source: input, kind: source, options: options, destination: destination,
                                             progress: reportProgress)
                }
            }
            let result = await withTaskCancellationHandler { await job.value } onCancel: { job.cancel() }
            guard !Task.isCancelled else { try? FileManager.default.removeItem(at: destination); return }
            switch result {
            case .success(let exported):
                self.exportResult = exported
                self.announcer.announce(self.exportResultText, important: true)
            case .failure(let failure):
                try? FileManager.default.removeItem(at: destination)
                if !(failure is CancellationError) { self.present(failure) { .exportFailed(detail: $0) } }
            }
            self.activity = .idle
        }
    }

    /// File, length, format, measured loudness and size, for the screen and VoiceOver.
    var exportResultText: String {
        guard let result = exportResult else { return "" }
        var parts = [l10n("export.result.file", result.url.lastPathComponent, l10n.clock(result.seconds)),
                     ExportText.format(result.options, l10n: l10n),
                     l10n("export.result.level", l10n.number(result.lufs, fractionDigits: 1),
                          l10n.number(result.truePeakDbtp, fractionDigits: 1)),
                     l10n("export.result.size", l10n.number(Double(result.bytes) / 1_048_576, fractionDigits: 1))]
        if result.limitingDb > 0.5 {
            parts.append(l10n("export.result.limited", l10n.number(result.limitingDb, fractionDigits: 1)))
        }
        if result.clippedSamples > 0 {
            parts.append(l10n("export.result.clipped", l10n.number(Double(result.clippedSamples))))
        }
        return parts.joined(separator: " ")
    }

    // MARK: Vocal separation (phase 7)

    static let separatorKey = "maqamstudio.separator"

    /// The last separation of this project's song, while its stems exist.
    @Published private(set) var separation: SeparationInfo?
    /// Core ML models the user added that follow the separation contract.
    @Published private(set) var separationModels: [CoreMLSeparator] = []

    var separators: [VocalSeparator] { [ClassicalSeparator()] + separationModels }

    var separatorId: String {
        get {
            let stored = UserDefaults.standard.string(forKey: Self.separatorKey) ?? "classical"
            return separators.contains { $0.id == stored } ? stored : "classical"
        }
        set {
            objectWillChange.send()
            UserDefaults.standard.set(newValue, forKey: Self.separatorKey)
        }
    }

    var separationIsFresh: Bool {
        guard let separation, let original = document?.original else { return false }
        return separation.sourceSHA256 == original.sha256
    }

    func refreshSeparationModels() {
        separationModels = SeparationModels.installed()
    }

    /// Splits the song into a vocal and an accompaniment, both kept beside the
    /// project; the original is not touched.
    func separateVocal() {
        guard activity == .idle, let store, let current = document, let original = current.original,
              let source = store.originalURL(for: current),
              let separator = separators.first(where: { $0.id == separatorId }) else { return }
        importTask = Task { [weak self] in
            guard let self else { return }
            self.beginRendering(task: "separation", announcementKey: "announce.separation")
            do {
                try store.prepareRendersFolder(for: current.id)
                // Two stereo float stems.
                try store.ensureSpace(forBytes: Int64(Double(original.frames) / original.sampleRate * 48000 * 16) + 1_000_000)
            } catch {
                self.present(error) { .projectSaveFailed(detail: $0) }
                self.activity = .idle
                return
            }
            self.progressBase = 0
            self.progressSpan = 1
            let projectId = current.id
            let token = UUID().uuidString
            let vocalsTemporary = store.renderURL(for: projectId, fileName: "vocals-\(token).caf")
            let restTemporary = store.renderURL(for: projectId, fileName: "accompaniment-\(token).caf")
            let reportProgress: @Sendable (Double) -> Void = { [weak self] fraction in
                Task { @MainActor in self?.renderProgress(fraction) }
            }
            let job = Task.detached(priority: .userInitiated) { () -> Result<(sampleRate: Double, frames: Int), Error> in
                Result {
                    try SeparationRenderer.separate(source: source, separator: separator, vocals: vocalsTemporary,
                                                    accompaniment: restTemporary, progress: reportProgress)
                }
            }
            let result = await withTaskCancellationHandler { await job.value } onCancel: { job.cancel() }
            let cleanUp = { for url in [vocalsTemporary, restTemporary] { try? FileManager.default.removeItem(at: url) } }
            guard !Task.isCancelled, self.document?.id == projectId else { cleanUp(); return }
            switch result {
            case .success(let stems):
                do {
                    try Self.replace(store.renderURL(for: projectId, fileName: SeparationInfo.vocalsFile), with: vocalsTemporary)
                    try Self.replace(store.renderURL(for: projectId, fileName: SeparationInfo.accompanimentFile), with: restTemporary)
                    let info = SeparationInfo(engineId: separator.id, sourceSHA256: original.sha256, separatedAt: Date(),
                                              sampleRate: stems.sampleRate, frames: stems.frames)
                    try store.saveSeparationInfo(info, for: projectId)
                    self.separation = info
                    self.listening = .vocals
                    self.reloadKeepingPosition()
                    self.announcer.announce(self.l10n("announce.separated"), important: true)
                } catch {
                    cleanUp()
                    self.present(error) { .projectSaveFailed(detail: $0) }
                }
            case .failure(let failure):
                cleanUp()
                if !(failure is CancellationError) { self.present(failure) { .projectSaveFailed(detail: $0) } }
            }
            self.activity = .idle
        }
    }

    /// A new project whose original is the separated vocal, ready for pitch
    /// analysis, tuning and Auto Studio. The song's project keeps its stems.
    func startProjectFromVocal() {
        guard activity == .idle, separationIsFresh, let store, let current = document else { return }
        let vocals = store.renderURL(for: current.id, fileName: SeparationInfo.vocalsFile)
        importAudio(from: vocals, name: l10n("separation.projectname", current.name))
    }

    /// Compiles and checks a Core ML model, then offers it as an engine.
    func installSeparationModel(from url: URL) {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        do {
            let installed = try SeparationModels.install(from: url)
            refreshSeparationModels()
            separatorId = installed.id
            announcer.announce(l10n("announce.modeladded", installed.name), important: true)
        } catch {
            present(error) { .separationModel(.unreadable(detail: $0)) }
        }
    }

    func removeSeparationModel(_ model: CoreMLSeparator) {
        try? SeparationModels.remove(model)
        refreshSeparationModels()
    }

    // MARK: Maqam and tonic detection (phase 5)

    private func updateDetection() {
        guard let notes = pitch?.notes else { detection = nil; return }
        detection = MaqamDetectionResult.detect(notes: notes, maqamat: MaqamCatalog.builtins + library.maqamat)
    }

    /// After a new recording or import: if nothing is chosen yet and the
    /// detection is confident, use it, say so, and leave it undoable.
    private func applyDetectionIfUnchosen() {
        guard document?.maqam.maqamId == nil, let detection, detection.enoughData, let best = detection.best,
              best.probability >= MaqamDetectionResult.applyAutomaticallyAbove else { return }
        applyDetection(best, automatically: true)
    }

    /// Uses a detected maqam and tonic (the tonic exactly as sung).
    func applyDetection(_ candidate: MaqamDetectionResult.Candidate, automatically: Bool = false) {
        edit(actionKey: "action.detectionused") { editable in
            editable.maqam.maqamId = candidate.maqam.id
            editable.maqam.tonicHz = candidate.tonicHz
            editable.maqam.degreeOffsets = [:]
            editable.maqam.manuallyChosen = false
            editable.maqam.customDefinition = candidate.maqam.isCustom ? candidate.maqam : nil
        }
        announcer.announce(l10n(automatically ? "announce.detected.auto" : "announce.detected.applied",
                                candidate.maqam.name(l10n), PitchNaming.label(hz: candidate.tonicHz, l10n: l10n),
                                l10n.percent(candidate.probability)), important: automatically)
    }

    // MARK: Maqam (manual choice)

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
        updateDetection()
        announcer.announce(l10n("announce.maqamsaved", maqam.name(l10n)))
        return true
    }

    func deleteCustomMaqam(id: String) {
        do {
            try library.delete(maqamId: id)
            objectWillChange.send()
            updateDetection()
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
            updateDetection()
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
