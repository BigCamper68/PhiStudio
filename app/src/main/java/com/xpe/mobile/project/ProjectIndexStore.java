package com.xpe.mobile.project;

import com.xpe.mobile.project.ProjectLibrary.State;
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
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** ProjectIndexStore responsibilities extracted from ProjectLibrary. */
final class ProjectIndexStore {
    private final ProjectLibrary delegateOwner;

    ProjectIndexStore(ProjectLibrary delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    State readState() throws IOException {
        if (!delegateOwner.indexFile.exists()) return new State(Collections.emptyList(), null);
        if (!delegateOwner.indexFile.isFile()) throw new IOException("Project library index is not a file");
        if (delegateOwner.indexFile.length() > ProjectLibrary.MAX_INDEX_BYTES) throw new IOException("Project library index is too large");
        try {
            String source = new String(Files.readAllBytes(delegateOwner.indexFile.toPath()), StandardCharsets.UTF_8);
            JSONObject rootJson = new JSONObject(source);
            int version = rootJson.getInt("formatVersion");
            if (version != ProjectLibrary.FORMAT_VERSION) {
                throw new IOException("Unsupported project library formatVersion: " + version);
            }
            JSONArray array = rootJson.getJSONArray("projects");
            if (array.length() > ProjectLibrary.MAX_PROJECTS) throw new IOException("Project library has too many entries");
            List<ProjectRecord> records = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                ProjectRecord record = ProjectRecord.fromJson(array.getJSONObject(index));
                ProjectWorkspaceFiles.validateId(record.getId());
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

    void writeState(State state) throws IOException {
        JSONObject rootJson;
        try {
            rootJson = new JSONObject().put("formatVersion", ProjectLibrary.FORMAT_VERSION);
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

        File temporary = new File(delegateOwner.root, "index.json.tmp");
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
                Files.move(temporary.toPath(), delegateOwner.indexFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), delegateOwner.indexFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temporary.delete();
        }
    }

    void ensureDirectories() throws IOException {
        if (delegateOwner.root.exists() && !delegateOwner.root.isDirectory()) throw new IOException("Project library path is not a directory");
        if (!delegateOwner.root.isDirectory() && !delegateOwner.root.mkdirs()) throw new IOException("Unable to create project library");
        if (delegateOwner.projectsDirectory.exists() && !delegateOwner.projectsDirectory.isDirectory()) {
            throw new IOException("Project workspaces path is not a directory");
        }
        if (!delegateOwner.projectsDirectory.isDirectory() && !delegateOwner.projectsDirectory.mkdirs()) {
            throw new IOException("Unable to create project workspaces directory");
        }
    }
}
