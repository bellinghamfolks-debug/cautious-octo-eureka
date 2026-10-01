import SwiftUI

/// Every saved project, newest first: open, duplicate, delete, or start a new one.
///
/// Each row is one VoiceOver element whose label says the name, date, length
/// and whether it is open or needs recovery; duplicate and delete are custom
/// actions on the row as well as swipe actions, so no gesture is required.
struct ProjectsView: View {
    @EnvironmentObject private var l10n: L10n
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var pendingDeletion: ProjectSummary?

    var body: some View {
        NavigationStack {
            Group {
                if model.projects.isEmpty {
                    VStack(spacing: 12) {
                        Text(l10n("projects.empty.title")).font(.title3.bold()).accessibilityAddTraits(.isHeader)
                        Text(l10n("projects.empty.body")).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(model.projects) { project in
                            row(project)
                        }
                    }
                }
            }
            .navigationTitle(l10n("projects.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("action.close")) { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        model.newProject()
                        dismiss()
                    } label: {
                        Label(l10n("action.newproject"), systemImage: "doc.badge.plus")
                    }
                }
            }
            .confirmationDialog(l10n("projects.delete.title"),
                                isPresented: Binding(get: { pendingDeletion != nil },
                                                     set: { if !$0 { pendingDeletion = nil } }),
                                titleVisibility: .visible,
                                presenting: pendingDeletion) { project in
                Button(l10n("action.delete"), role: .destructive) { model.deleteProject(id: project.id) }
                Button(l10n("action.cancel"), role: .cancel) {}
            } message: { project in
                Text(l10n("projects.delete.message", project.name))
            }
            .onAppear { model.refreshProjects() }
        }
        .environment(\.locale, l10n.locale)
        .environment(\.layoutDirection, l10n.layoutDirection)
    }

    private func row(_ project: ProjectSummary) -> some View {
        let isOpen = model.document?.id == project.id
        return Button {
            model.openProject(id: project.id)
            dismiss()
        } label: {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: project.needsRecovery ? "lifepreserver" : (isOpen ? "waveform.circle.fill" : "waveform.circle"))
                    .font(.title2)
                    .foregroundStyle(project.needsRecovery ? Color.orange : Color.accentColor)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(project.name).font(.headline)
                    Text(details(project)).font(.footnote).foregroundStyle(.secondary)
                    if project.needsRecovery {
                        Text(l10n("projects.needsrecovery")).font(.footnote).foregroundStyle(.orange)
                    } else if isOpen {
                        Text(l10n("projects.open")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenLabel(project, isOpen: isOpen))
        .accessibilityHint(l10n("projects.row.hint"))
        .accessibilityAddTraits(isOpen ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction(named: l10n("action.duplicate")) { model.duplicateProject(id: project.id) }
        .accessibilityAction(named: l10n("action.delete")) { pendingDeletion = project }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                pendingDeletion = project
            } label: {
                Label(l10n("action.delete"), systemImage: "trash")
            }
            Button {
                model.duplicateProject(id: project.id)
            } label: {
                Label(l10n("action.duplicate"), systemImage: "plus.square.on.square")
            }
            .tint(.blue)
        }
    }

    private func details(_ project: ProjectSummary) -> String {
        var parts = [l10n.date(project.modifiedAt)]
        if let duration = project.duration { parts.append(l10n.clock(duration)) }
        else { parts.append(l10n("projects.noaudio")) }
        return parts.joined(separator: " · ")
    }

    private func spokenLabel(_ project: ProjectSummary, isOpen: Bool) -> String {
        var parts = [project.name, l10n("projects.modified", l10n.date(project.modifiedAt))]
        if let duration = project.duration { parts.append(l10n.spokenDuration(duration)) }
        else { parts.append(l10n("projects.noaudio")) }
        if project.needsRecovery { parts.append(l10n("projects.needsrecovery")) }
        else if isOpen { parts.append(l10n("projects.open")) }
        return parts.joined(separator: l10n.listSeparator)
    }
}
