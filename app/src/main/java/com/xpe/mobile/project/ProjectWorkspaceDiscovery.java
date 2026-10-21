package com.xpe.mobile.project;

import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageException;
import com.xpe.mobile.packageio.PackageWorkspaceLoader;
import com.xpe.mobile.project.ProjectLibrary.Discovery;
import com.xpe.mobile.project.ProjectLibrary.OpenResult;
import com.xpe.mobile.project.ProjectLibrary.State;
import com.xpe.mobile.project.ProjectLibrary.WorkspaceIssue;
import com.xpe.mobile.project.ProjectLibrary.WorkspaceStatus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ProjectWorkspaceDiscovery responsibilities extracted from ProjectLibrary. */
final class ProjectWorkspaceDiscovery {
    private final ProjectLibrary delegateOwner;

    ProjectWorkspaceDiscovery(ProjectLibrary delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    Discovery discoverWorkspaces() throws IOException {
        delegateOwner.projectIndexStore.ensureDirectories();
        State state = delegateOwner.projectIndexStore.readState();
        List<WorkspaceIssue> issues = new ArrayList<>();
        Set<String> indexed = new HashSet<>();
        for (ProjectRecord record : state.getProjects()) {
            indexed.add(record.getId());
            try {
                File workspace = delegateOwner.projectWorkspaceFiles.resolveProjectDirectory(record.getId());
                if (!workspace.isDirectory() || Files.isSymbolicLink(workspace.toPath())) {
                    issues.add(new WorkspaceIssue(WorkspaceStatus.MISSING, record.getId(),
                            record.getName(), WorkspaceIssue.Reason.MISSING_WORKSPACE, true));
                    continue;
                }
                new PackageWorkspaceLoader()
                        .validateIndexedWorkspace(workspace, record.getChartPath());
            } catch (PackageException exception) {
                WorkspaceStatus status = exception.getCode()
                        == PackageException.Code.UNSUPPORTED_CHART_FORMAT
                        ? WorkspaceStatus.UNSUPPORTED : WorkspaceStatus.DAMAGED;
                issues.add(new WorkspaceIssue(status, record.getId(), record.getName(),
                        ProjectWorkspaceFiles.safeMessage(exception), true));
            } catch (IOException | RuntimeException exception) {
                issues.add(new WorkspaceIssue(WorkspaceStatus.DAMAGED, record.getId(),
                        record.getName(), ProjectWorkspaceFiles.safeMessage(exception), true));
            }
        }

        File[] workspaces = delegateOwner.projectsDirectory.listFiles();
        if (workspaces == null) throw new IOException("Unable to read project workspaces directory");
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, workspaces);
        sorted.sort(Comparator.comparing(File::getName));
        for (File workspace : sorted) {
            String id = workspace.getName();
            if (indexed.contains(id)) continue;
            if (!ProjectWorkspaceFiles.isValidId(id) || !workspace.isDirectory() || Files.isSymbolicLink(workspace.toPath())) {
                issues.add(new WorkspaceIssue(WorkspaceStatus.DAMAGED, id, id,
                        WorkspaceIssue.Reason.UNSAFE_WORKSPACE, false));
                continue;
            }
            try {
                String projectName = new PackageWorkspaceLoader()
                        .inspectProjectName(workspace, id + ".zip");
                issues.add(new WorkspaceIssue(WorkspaceStatus.RECOVERABLE, id,
                        ProjectWorkspaceFiles.safeName(projectName),
                        WorkspaceIssue.Reason.RECOVERABLE_WORKSPACE, false));
            } catch (PackageException exception) {
                WorkspaceStatus status = exception.getCode()
                        == PackageException.Code.UNSUPPORTED_CHART_FORMAT
                        ? WorkspaceStatus.UNSUPPORTED : WorkspaceStatus.DAMAGED;
                issues.add(new WorkspaceIssue(status, id, id, ProjectWorkspaceFiles.safeMessage(exception), false));
            } catch (IOException | RuntimeException exception) {
                issues.add(new WorkspaceIssue(
                        WorkspaceStatus.DAMAGED, id, id, ProjectWorkspaceFiles.safeMessage(exception), false));
            }
        }
        return new Discovery(state, issues);
    }

    OpenResult recoverOrphan(String id, long nowMillis) throws IOException {
        delegateOwner.projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        State current = delegateOwner.projectIndexStore.readState();
        if (current.find(id) != null) throw new IOException("Project is already indexed: " + id);
        File workspace = delegateOwner.projectWorkspaceFiles.resolveProjectDirectory(id);
        ChartPackage chartPackage = new PackageWorkspaceLoader().load(workspace, id + ".zip");
        ProjectRecord record = new ProjectRecord(id, ProjectWorkspaceFiles.safeName(chartPackage.getProjectName()),
                id + ".zip", chartPackage.getChartPath(), chartPackage.getAudioPath(),
                chartPackage.getIllustrationPath(), nowMillis, nowMillis);
        List<ProjectRecord> records = new ArrayList<>(current.getProjects());
        records.add(record);
        State updated = new State(records, id);
        delegateOwner.projectIndexStore.writeState(updated);
        return new OpenResult(chartPackage, record, updated);
    }

    State removeUnindexedWorkspace(String id) throws IOException {
        delegateOwner.projectIndexStore.ensureDirectories();
        if (!ProjectWorkspaceFiles.isSafeWorkspaceChildName(id)) throw new IOException("Invalid workspace name");
        State current = delegateOwner.projectIndexStore.readState();
        if (ProjectWorkspaceFiles.isValidId(id) && current.find(id) != null) {
            throw new IOException("Indexed projects must be removed through the library");
        }
        delegateOwner.projectWorkspaceFiles.deleteProjectDirectory(delegateOwner.projectWorkspaceFiles.resolveProjectDirectory(id));
        return current;
    }
}
