package com.xpe.mobile.project;

import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageWorkspaceCopier;
import com.xpe.mobile.packageio.PackageWorkspaceLoader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ProjectLibrary {
    final ProjectIndexStore projectIndexStore = new ProjectIndexStore(this);
    private final ProjectWorkspaceDiscovery projectWorkspaceDiscovery = new ProjectWorkspaceDiscovery(this);
    final ProjectWorkspaceFiles projectWorkspaceFiles = new ProjectWorkspaceFiles(this);

    public static final int FORMAT_VERSION = 1;
    static final int MAX_PROJECTS = 2048;
    static final long MAX_INDEX_BYTES = 2L * 1024L * 1024L;

    public static final class State {
        private final List<ProjectRecord> projects;
        private final String currentProjectId;

        State(List<ProjectRecord> projects, String currentProjectId) {
            List<ProjectRecord> sorted = new ArrayList<>(projects);
            sorted.sort(Comparator.comparingLong(ProjectRecord::getLastOpenedAtMillis).reversed()
                    .thenComparing(Comparator.comparingLong(
                            ProjectRecord::getImportedAtMillis).reversed())
                    .thenComparing(ProjectRecord::getName)
                    .thenComparing(ProjectRecord::getId));
            this.projects = Collections.unmodifiableList(sorted);
            this.currentProjectId = currentProjectId;
        }

        public List<ProjectRecord> getProjects() {
            return projects;
        }

        public String getCurrentProjectId() {
            return currentProjectId;
        }

        public ProjectRecord find(String id) {
            if (id == null) return null;
            for (ProjectRecord project : projects) {
                if (id.equals(project.getId())) return project;
            }
            return null;
        }
    }

    public static final class OpenResult {
        private final ChartPackage chartPackage;
        private final ProjectRecord record;
        private final State state;

        OpenResult(ChartPackage chartPackage, ProjectRecord record, State state) {
            this.chartPackage = chartPackage;
            this.record = record;
            this.state = state;
        }

        public ChartPackage getChartPackage() {
            return chartPackage;
        }

        public ProjectRecord getRecord() {
            return record;
        }

        public State getState() {
            return state;
        }
    }

    public enum WorkspaceStatus {
        RECOVERABLE,
        MISSING,
        DAMAGED,
        UNSUPPORTED
    }

    public static final class WorkspaceIssue {
        private final WorkspaceStatus status;
        private final String projectId;
        private final String displayName;
        private final String message;
        private final boolean indexed;

        WorkspaceIssue(WorkspaceStatus status, String projectId, String displayName,
                       String message, boolean indexed) {
            this.status = status;
            this.projectId = projectId;
            this.displayName = displayName;
            this.message = message;
            this.indexed = indexed;
        }

        public WorkspaceStatus getStatus() {
            return status;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getMessage() {
            return message;
        }

        public boolean isIndexed() {
            return indexed;
        }
    }

    public static final class Discovery {
        private final State state;
        private final List<WorkspaceIssue> issues;

        Discovery(State state, List<WorkspaceIssue> issues) {
            this.state = state;
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }

        public State getState() {
            return state;
        }

        public List<WorkspaceIssue> getIssues() {
            return issues;
        }
    }

    final File root;
    final File projectsDirectory;
    final File indexFile;

    public ProjectLibrary(File root) {
        this.root = root;
        this.projectsDirectory = new File(root, "projects");
        this.indexFile = new File(root, "index.json");
    }

    public synchronized State load() throws IOException {
        projectIndexStore.ensureDirectories();
        return projectIndexStore.readState();
    }

    public synchronized Discovery discoverWorkspaces() throws IOException {
        return projectWorkspaceDiscovery.discoverWorkspaces();
    }

    public synchronized File getProjectWorkspace(String id) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        return projectWorkspaceFiles.resolveProjectDirectory(id);
    }

    public synchronized File getProjectResource(String id, String relativePath) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IOException("Project resource path is missing");
        }
        File workspace = projectWorkspaceFiles.resolveProjectDirectory(id);
        File resource = new File(workspace, relativePath.replace('/', File.separatorChar));
        String rootPath = workspace.getCanonicalPath();
        String resourcePath = resource.getCanonicalPath();
        if (!resourcePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Project resource path escapes the workspace");
        }
        return resource;
    }

    public synchronized File workspaceForNewProject(String id) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        File workspace = projectWorkspaceFiles.resolveProjectDirectory(id);
        if (workspace.exists()) throw new IOException("Project workspace already exists: " + id);
        return workspace;
    }

    public synchronized State addImportedProject(String id, ChartPackage chartPackage,
                                                 long nowMillis) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        if (chartPackage == null || !chartPackage.getWorkspace().isDirectory()) {
            throw new IOException("Imported project workspace is missing");
        }
        File expected = projectWorkspaceFiles.resolveProjectDirectory(id);
        if (!expected.getCanonicalFile().equals(chartPackage.getWorkspace().getCanonicalFile())) {
            throw new IOException("Imported package is outside the project library");
        }
        State current = projectIndexStore.readState();
        if (current.find(id) != null) throw new IOException("Project ID already exists: " + id);
        List<ProjectRecord> records = new ArrayList<>(current.getProjects());
        records.add(new ProjectRecord(id, ProjectWorkspaceFiles.safeName(chartPackage.getProjectName()),
                ProjectWorkspaceFiles.safeSourceName(chartPackage.getSourceDisplayName()), chartPackage.getChartPath(),
                chartPackage.getAudioPath(), chartPackage.getIllustrationPath(),
                nowMillis, nowMillis));
        State updated = new State(records, id);
        projectIndexStore.writeState(updated);
        return updated;
    }

    public synchronized OpenResult openProject(String id, long nowMillis) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        State current = projectIndexStore.readState();
        ProjectRecord record = current.find(id);
        if (record == null) throw new IOException("Project is not present in the library: " + id);
        ChartPackage chartPackage = new PackageWorkspaceLoader().load(
                projectWorkspaceFiles.resolveProjectDirectory(id), record.getSourceDisplayName());
        List<ProjectRecord> records = new ArrayList<>();
        ProjectRecord touched = null;
        for (ProjectRecord candidate : current.getProjects()) {
            if (candidate.getId().equals(id)) {
                touched = candidate.withLastOpened(nowMillis);
                records.add(touched);
            } else {
                records.add(candidate);
            }
        }
        State updated = new State(records, id);
        projectIndexStore.writeState(updated);
        return new OpenResult(chartPackage, touched, updated);
    }

    public synchronized State renameProject(String id, String newName) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        String normalized = ProjectWorkspaceFiles.normalizeLocalName(newName);
        State current = projectIndexStore.readState();
        if (current.find(id) == null) throw new IOException("Project is not present in the library: " + id);
        List<ProjectRecord> records = new ArrayList<>();
        for (ProjectRecord record : current.getProjects()) {
            records.add(record.getId().equals(id) ? record.withName(normalized) : record);
        }
        State updated = new State(records, current.getCurrentProjectId());
        projectIndexStore.writeState(updated);
        return updated;
    }

    public synchronized OpenResult duplicateProject(String sourceId, String newId,
                                                    long nowMillis) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(sourceId);
        ProjectWorkspaceFiles.validateId(newId);
        State current = projectIndexStore.readState();
        ProjectRecord sourceRecord = current.find(sourceId);
        if (sourceRecord == null) throw new IOException("Project is not present in the library: " + sourceId);
        if (current.find(newId) != null) throw new IOException("Project ID already exists: " + newId);
        File source = projectWorkspaceFiles.resolveProjectDirectory(sourceId);
        new PackageWorkspaceLoader().load(source, sourceRecord.getSourceDisplayName());
        File target = projectWorkspaceFiles.resolveProjectDirectory(newId);
        if (target.exists()) throw new IOException("Duplicate workspace already exists: " + newId);
        File staging = new File(projectsDirectory, ".duplicate-" + newId + ".tmp");
        if (staging.exists()) throw new IOException("Duplicate staging workspace already exists");

        boolean moved = false;
        try {
            new PackageWorkspaceCopier().copy(source, staging);
            new PackageWorkspaceLoader().load(staging, sourceRecord.getSourceDisplayName());
            ProjectWorkspaceFiles.moveDirectory(staging, target);
            moved = true;
            ChartPackage duplicated = new PackageWorkspaceLoader().load(
                    target, sourceRecord.getSourceDisplayName());
            String duplicateName = ProjectWorkspaceFiles.nextCopyName(sourceRecord.getName(), current.getProjects());
            ProjectRecord duplicateRecord = new ProjectRecord(newId, duplicateName,
                    sourceRecord.getSourceDisplayName(), duplicated.getChartPath(),
                    duplicated.getAudioPath(), duplicated.getIllustrationPath(), nowMillis, nowMillis);
            List<ProjectRecord> records = new ArrayList<>(current.getProjects());
            records.add(duplicateRecord);
            State updated = new State(records, newId);
            projectIndexStore.writeState(updated);
            return new OpenResult(duplicated, duplicateRecord, updated);
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
            ProjectWorkspaceFiles.cleanupAfterFailedDuplicate(moved ? target : staging, exception);
            throw exception;
        }
    }

    public synchronized OpenResult recoverOrphan(String id, long nowMillis) throws IOException {
        return projectWorkspaceDiscovery.recoverOrphan(id, nowMillis);
    }

    public synchronized State removeUnindexedWorkspace(String id) throws IOException {
        return projectWorkspaceDiscovery.removeUnindexedWorkspace(id);
    }

    public synchronized State clearCurrentProject() throws IOException {
        projectIndexStore.ensureDirectories();
        State current = projectIndexStore.readState();
        State updated = new State(current.getProjects(), null);
        projectIndexStore.writeState(updated);
        return updated;
    }

    public synchronized State removeProject(String id) throws IOException {
        projectIndexStore.ensureDirectories();
        ProjectWorkspaceFiles.validateId(id);
        State current = projectIndexStore.readState();
        if (current.find(id) == null) throw new IOException("Project is not present in the library: " + id);
        List<ProjectRecord> records = new ArrayList<>();
        for (ProjectRecord record : current.getProjects()) {
            if (!record.getId().equals(id)) records.add(record);
        }
        String nextCurrent = id.equals(current.getCurrentProjectId()) ? null : current.getCurrentProjectId();
        State updated = new State(records, nextCurrent);
        projectIndexStore.writeState(updated);
        projectWorkspaceFiles.deleteProjectDirectory(projectWorkspaceFiles.resolveProjectDirectory(id));
        return updated;
    }

}
