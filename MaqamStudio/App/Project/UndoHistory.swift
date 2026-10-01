import Foundation

/// Undo and redo over snapshots of a value.
///
/// Snapshots rather than inverse operations: every edit in Maqam Studio is a
/// parameter change on a small value type, so storing the value before each
/// change is simple, exact, and cannot drift out of step with the edits.
struct UndoHistory<Value: Equatable> {
    struct Entry: Equatable {
        var value: Value
        /// Localization key describing the change, for "Undo rename" style labels.
        var actionKey: String
    }

    private(set) var undoStack: [Entry] = []
    private(set) var redoStack: [Entry] = []
    let limit: Int

    init(limit: Int = 200) {
        self.limit = max(1, limit)
    }

    var canUndo: Bool { !undoStack.isEmpty }
    var canRedo: Bool { !redoStack.isEmpty }
    var undoActionKey: String? { undoStack.last?.actionKey }
    var redoActionKey: String? { redoStack.last?.actionKey }

    /// Call before applying a change, with the value as it was.
    mutating func record(_ before: Value, actionKey: String) {
        undoStack.append(Entry(value: before, actionKey: actionKey))
        if undoStack.count > limit { undoStack.removeFirst(undoStack.count - limit) }
        redoStack.removeAll()
    }

    /// Returns the value to restore, given the current one, or nil.
    mutating func undo(current: Value) -> Entry? {
        guard let entry = undoStack.popLast() else { return nil }
        redoStack.append(Entry(value: current, actionKey: entry.actionKey))
        return entry
    }

    mutating func redo(current: Value) -> Entry? {
        guard let entry = redoStack.popLast() else { return nil }
        undoStack.append(Entry(value: current, actionKey: entry.actionKey))
        return entry
    }

    mutating func clear() {
        undoStack.removeAll()
        redoStack.removeAll()
    }
}
