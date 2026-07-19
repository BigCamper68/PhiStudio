package com.xpe.mobile.project;

import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageException;
import com.xpe.mobile.packageio.PackageWorkspaceCopier;
import com.xpe.mobile.packageio.PackageWorkspaceLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProjectLibrary {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_PROJECTS = 2048;
    private static final long MAX_INDEX_BYTES = 2L * 1024L * 1024L;

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

    private final File root;
    private final File projectsDirectory;
    private final File indexFile;

    public ProjectLibrary(File root) {
        this.root = root;
        this.projectsDirectory = new File(root, "projects");
        this.indexFile = new File(root, "index.json");
    }

    public synchronized State load() throws IOException {
        ensureDirectories();
        return readState();
    }

    public synchronized Discovery discoverWorkspaces() throws IOException {
        ensureDirectories();
        State state = readState();
        List<WorkspaceIssue> issues = new ArrayList<>();
        Set<String> indexed = new HashSet<>();
        for (ProjectRecord record : state.getProjects()) {
            indexed.add(record.getId());
            File workspace = resolveProjectDirectory(record.getId());
            if (!workspace.isDirectory() || Files.isSymbolicLink(workspace.toPath())) {
                issues.add(new WorkspaceIssue(WorkspaceStatus.MISSING, record.getId(),
                        record.getName(), "Indexed project workspace is missing or unsafe", true));
                continue;
            }
            try {
                new PackageWorkspaceLoader()
                        .validateIndexedWorkspace(workspace, record.getChartPath());
            } catch (PackageException exception) {
                WorkspaceStatus status = exception.getCode()
                        == PackageException.Code.UNSUPPORTED_CHART_FORMAT
                        ? WorkspaceStatus.UNSUPPORTED : WorkspaceStatus.DAMAGED;
                issues.add(new WorkspaceIssue(status, record.getId(), record.getName(),
                        safeMessage(exception), true));
            } catch (IOException | RuntimeException exception) {
                issues.add(new WorkspaceIssue(WorkspaceStatus.DAMAGED, record.getId(),
                        record.getName(), safeMessage(exception), true));
            }
        }

        File[] workspaces = projectsDirectory.listFiles();
        if (workspaces == null) throw new IOException("Unable to read project workspaces directory");
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, workspaces);
        sorted.sort(Comparator.comparing(File::getName));
        for (File workspace : sorted) {
            String id = workspace.getName();
            if (indexed.contains(id)) continue;
            if (!isValidId(id) || !workspace.isDirectory() || Files.isSymbolicLink(workspace.toPath())) {
                issues.add(new WorkspaceIssue(WorkspaceStatus.DAMAGED, id, id,
                        "Unindexed workspace has an unsafe name or type", false));
                continue;
            }
            try {
                String projectName = new PackageWorkspaceLoader()
                        .inspectProjectName(workspace, id + ".zip");
                issues.add(new WorkspaceIssue(WorkspaceStatus.RECOVERABLE, id,
                        safeName(projectName),
                        "Valid unindexed project workspace", false));
            } catch (PackageException exception) {
                WorkspaceStatus status = exception.getCode()
                        == PackageException.Code.UNSUPPORTED_CHART_FORMAT
                        ? WorkspaceStatus.UNSUPPORTED : WorkspaceStatus.DAMAGED;
                issues.add(new WorkspaceIssue(status, id, id, safeMessage(exception), false));
            } catch (IOException | RuntimeException exception) {
                issues.add(new WorkspaceIssue(
                        WorkspaceStatus.DAMAGED, id, id, safeMessage(exception), false));
            }
        }
        return new Discovery(state, issues);
    }

    public synchronized File getProjectWorkspace(String id) throws IOException {
        ensureDirectories();
        validateId(id);
        return resolveProjectDirectory(id);
    }

    public synchronized File getProjectResource(String id, String relativePath) throws IOException {
        ensureDirectories();
        validateId(id);
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IOException("Project resource path is missing");
        }
        File workspace = resolveProjectDirectory(id);
        File resource = new File(workspace, relativePath.replace('/', File.separatorChar));
        String rootPath = workspace.getCanonicalPath();
        String resourcePath = resource.getCanonicalPath();
        if (!resourcePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Project resource path escapes the workspace");
        }
        return resource;
    }

    public synchronized File workspaceForNewProject(String id) throws IOException {
        ensureDirectories();
        validateId(id);
        File workspace = resolveProjectDirectory(id);
        if (workspace.exists()) throw new IOException("Project workspace already exists: " + id);
        return workspace;
    }

    public synchronized State addImportedProject(String id, ChartPackage chartPackage,
                                                 long nowMillis) throws IOException {
        ensureDirectories();
        validateId(id);
        if (chartPackage == null || !chartPackage.getWorkspace().isDirectory()) {
            throw new IOException("Imported project workspace is missing");
        }
        File expected = resolveProjectDirectory(id);
        if (!expected.getCanonicalFile().equals(chartPackage.getWorkspace().getCanonicalFile())) {
            throw new IOException("Imported package is outside the project library");
        }
        State current = readState();
        if (current.find(id) != null) throw new IOException("Project ID already exists: " + id);
        List<ProjectRecord> records = new ArrayList<>(current.getProjects());
        records.add(new ProjectRecord(id, safeName(chartPackage.getProjectName()),
                safeSourceName(chartPackage.getSourceDisplayName()), chartPackage.getChartPath(),
                chartPackage.getAudioPath(), chartPackage.getIllustrationPath(),
                nowMillis, nowMillis));
        State updated = new State(records, id);
        writeState(updated);
        return updated;
    }

    public synchronized OpenResult openProject(String id, long nowMillis) throws IOException {
        ensureDirectories();
        validateId(id);
        State current = readState();
        ProjectRecord record = current.find(id);
        if (record == null) throw new IOException("Project is not present in the library: " + id);
        ChartPackage chartPackage = new PackageWorkspaceLoader().load(
                resolveProjectDirectory(id), record.getSourceDisplayName());
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
        writeState(updated);
        return new OpenResult(chartPackage, touched, updated);
    }

    public synchronized State renameProject(String id, String newName) throws IOException {
        ensureDirectories();
        validateId(id);
        String normalized = normalizeLocalName(newName);
        State current = readState();
        if (current.find(id) == null) throw new IOException("Project is not present in the library: " + id);
        List<ProjectRecord> records = new ArrayList<>();
        for (ProjectRecord record : current.getProjects()) {
            records.add(record.getId().equals(id) ? record.withName(normalized) : record);
        }
        State updated = new State(records, current.getCurrentProjectId());
        writeState(updated);
        return updated;
    }

    public synchronized OpenResult duplicateProject(String sourceId, String newId,
                                                    long nowMillis) throws IOException {
        ensureDirectories();
        validateId(sourceId);
        validateId(newId);
        State current = readState();
        ProjectRecord sourceRecord = current.find(sourceId);
        if (sourceRecord == null) throw new IOException("Project is not present in the library: " + sourceId);
        if (current.find(newId) != null) throw new IOException("Project ID already exists: " + newId);
        File source = resolveProjectDirectory(sourceId);
        new PackageWorkspaceLoader().load(source, sourceRecord.getSourceDisplayName());
        File target = resolveProjectDirectory(newId);
        if (target.exists()) throw new IOException("Duplicate workspace already exists: " + newId);
        File staging = new File(projectsDirectory, ".duplicate-" + newId + ".tmp");
        if (staging.exists()) throw new IOException("Duplicate staging workspace already exists");

        boolean moved = false;
        try {
            new PackageWorkspaceCopier().copy(source, staging);
            new PackageWorkspaceLoader().load(staging, sourceRecord.getSourceDisplayName());
            moveDirectory(staging, target);
            moved = true;
            ChartPackage duplicated = new PackageWorkspaceLoader().load(
                    target, sourceRecord.getSourceDisplayName());
            String duplicateName = nextCopyName(sourceRecord.getName(), current.getProjects());
            ProjectRecord duplicateRecord = new ProjectRecord(newId, duplicateName,
                    sourceRecord.getSourceDisplayName(), duplicated.getChartPath(),
                    duplicated.getAudioPath(), duplicated.getIllustrationPath(), nowMillis, nowMillis);
            List<ProjectRecord> records = new ArrayList<>(current.getProjects());
            records.add(duplicateRecord);
            State updated = new State(records, newId);
            writeState(updated);
            return new OpenResult(duplicated, duplicateRecord, updated);
        } catch (IOException | RuntimeException exception) {
            cleanupAfterFailedDuplicate(moved ? target : staging, exception);
            throw exception;
        }
    }

    public synchronized OpenResult recoverOrphan(String id, long nowMillis) throws IOException {
        ensureDirectories();
        validateId(id);
        State current = readState();
        if (current.find(id) != null) throw new IOException("Project is already indexed: " + id);
        File workspace = resolveProjectDirectory(id);
        ChartPackage chartPackage = new PackageWorkspaceLoader().load(workspace, id + ".zip");
        ProjectRecord record = new ProjectRecord(id, safeName(chartPackage.getProjectName()),
                id + ".zip", chartPackage.getChartPath(), chartPackage.getAudioPath(),
                chartPackage.getIllustrationPath(), nowMillis, nowMillis);
        List<ProjectRecord> records = new ArrayList<>(current.getProjects());
        records.add(record);
        State updated = new State(records, id);
        writeState(updated);
        return new OpenResult(chartPackage, record, updated);
    }

    public synchronized State removeUnindexedWorkspace(String id) throws IOException {
        ensureDirectories();
        if (!isSafeWorkspaceChildName(id)) throw new IOException("Invalid workspace name");
        State current = readState();
        if (isValidId(id) && current.find(id) != null) {
            throw new IOException("Indexed projects must be removed through the library");
        }
        deleteProjectDirectory(resolveProjectDirectory(id));
        return current;
    }

    public synchronized State clearCurrentProject() throws IOException {
        ensureDirectories();
        State current = readState();
        State updated = new State(current.getProjects(), null);
        writeState(updated);
        return updated;
    }

    public synchronized State removeProject(String id) throws IOException {
        ensureDirectories();
        validateId(id);
        State current = readState();
        if (current.find(id) == null) throw new IOException("Project is not present in the library: " + id);
        List<ProjectRecord> records = new ArrayList<>();
        for (ProjectRecord record : current.getProjects()) {
            if (!record.getId().equals(id)) records.add(record);
        }
        String nextCurrent = id.equals(current.getCurrentProjectId()) ? null : current.getCurrentProjectId();
        State updated = new State(records, nextCurrent);
        writeState(updated);
        deleteProjectDirectory(resolveProjectDirectory(id));
        return updated;
    }

    private State readState() throws IOException {
        if (!indexFile.exists()) return new State(Collections.emptyList(), null);
        if (!indexFile.isFile()) throw new IOException("Project library index is not a file");
        if (indexFile.length() > MAX_INDEX_BYTES) throw new IOException("Project library index is too large");
        try {
            String source = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
            JSONObject rootJson = new JSONObject(source);
            int version = rootJson.getInt("formatVersion");
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported project library formatVersion: " + version);
            }
            JSONArray array = rootJson.getJSONArray("projects");
            if (array.length() > MAX_PROJECTS) throw new IOException("Project library has too many entries");
            List<ProjectRecord> records = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                ProjectRecord record = ProjectRecord.fromJson(array.getJSONObject(index));
                validateId(record.getId());
                if (!ids.add(record.getId())) {
                    throw new IOException("Duplicate project ID in library index: " + record.getId());
                }
                records.add(record);
            }
            String currentId = rootJson.isNull("currentProjectId")
                    ? null : rootJson.optString("currentProjectId", null);
            if (currentId != null && !ids.contains(currentId)) {
                throw new IOException("Current project is missing from the library index");
            }
            return new State(records, currentId);
        } catch (JSONException exception) {
            throw new IOException("Project library index is malformed", exception);
        }
    }

    private void writeState(State state) throws IOException {
        JSONObject rootJson;
        try {
            rootJson = new JSONObject().put("formatVersion", FORMAT_VERSION);
            rootJson.put("currentProjectId",
                    state.getCurrentProjectId() == null ? JSONObject.NULL : state.getCurrentProjectId());
            JSONArray projects = new JSONArray();
            List<ProjectRecord> deterministic = new ArrayList<>(state.getProjects());
            deterministic.sort(Comparator.comparing(ProjectRecord::getId));
            for (ProjectRecord record : deterministic) projects.put(record.toJson());
            rootJson.put("projects", projects);
        } catch (JSONException exception) {
            throw new IOException("Unable to serialize project library index", exception);
        }

        File temporary = new File(root, "index.json.tmp");
        byte[] data;
        try {
            data = rootJson.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new IOException("Unable to serialize project library index", exception);
        }
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data);
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), indexFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), indexFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temporary.delete();
        }
    }

    private void ensureDirectories() throws IOException {
        if (root.exists() && !root.isDirectory()) throw new IOException("Project library path is not a directory");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Unable to create project library");
        if (projectsDirectory.exists() && !projectsDirectory.isDirectory()) {
            throw new IOException("Project workspaces path is not a directory");
        }
        if (!projectsDirectory.isDirectory() && !projectsDirectory.mkdirs()) {
            throw new IOException("Unable to create project workspaces directory");
        }
    }

    private File resolveProjectDirectory(String id) throws IOException {
        File result = new File(projectsDirectory, id);
        String parent = projectsDirectory.getCanonicalPath();
        String child = result.getCanonicalPath();
        if (!child.startsWith(parent + File.separator)) throw new IOException("Unsafe project ID");
        return result;
    }

    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("Invalid project ID");
        }
    }

    private static String safeName(String value) {
        String normalized = sanitizeDisplayText(value, 160);
        return normalized.isEmpty() ? "Imported project" : normalized;
    }

    private static String safeSourceName(String value) {
        String normalized = sanitizeDisplayText(value, 240);
        return normalized.isEmpty() ? "Imported package.zip" : normalized;
    }

    private static String normalizeLocalName(String value) throws IOException {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new IOException("Project name cannot be empty");
        if (trimmed.length() > 160) throw new IOException("Project name is too long");
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IOException("Project name cannot contain control characters");
            }
        }
        return trimmed;
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private static boolean isSafeWorkspaceChildName(String name) {
        return name != null && name.matches("[A-Za-z0-9._-]{1,160}")
                && !name.equals(".") && !name.equals("..");
    }

    private static String nextCopyName(String sourceName, List<ProjectRecord> records) {
        Set<String> names = new HashSet<>();
        for (ProjectRecord record : records) names.add(record.getName().toLowerCase(Locale.ROOT));
        String suffixText = " — Copy";
        String baseSource = sourceName.length() > 160 - suffixText.length()
                ? sourceName.substring(0, 160 - suffixText.length()).trim() : sourceName;
        String base = baseSource + suffixText;
        String candidate = base;
        int suffix = 2;
        while (names.contains(candidate.toLowerCase(Locale.ROOT))) {
            String numberedSuffix = " " + suffix++;
            String shortened = base.length() > 160 - numberedSuffix.length()
                    ? base.substring(0, 160 - numberedSuffix.length()).trim() : base;
            candidate = shortened + numberedSuffix;
        }
        return candidate;
    }

    private static String sanitizeDisplayText(String value, int maximumLength) {
        String input = value == null ? "" : value.trim();
        StringBuilder sanitized = new StringBuilder(Math.min(input.length(), maximumLength));
        boolean previousWhitespace = false;
        for (int index = 0; index < input.length() && sanitized.length() < maximumLength; index++) {
            char character = input.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                if (!previousWhitespace && sanitized.length() > 0) sanitized.append(' ');
                previousWhitespace = true;
            } else {
                sanitized.append(character);
                previousWhitespace = false;
            }
        }
        return sanitized.toString().trim();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static void moveDirectory(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static void cleanupAfterFailedDuplicate(File directory, Throwable original) {
        try {
            deleteRecursively(directory);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private void deleteProjectDirectory(File directory) throws IOException {
        if (!directory.exists()) return;
        String parent = projectsDirectory.getCanonicalPath();
        if (Files.isSymbolicLink(directory.toPath())) {
            File directParent = directory.getAbsoluteFile().getParentFile();
            if (directParent == null || !directParent.getCanonicalPath().equals(parent)) {
                throw new IOException("Unsafe project symbolic link");
            }
            Files.delete(directory.toPath());
            return;
        }
        String child = directory.getCanonicalPath();
        if (!child.startsWith(parent + File.separator)) throw new IOException("Unsafe project directory");
        deleteRecursively(directory);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath());
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Unable to remove local project data");
    }
}
