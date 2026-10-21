import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageImporter;
import com.xpe.mobile.packageio.PackageWorkspaceLoader;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.project.ProjectRecord;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/** Standalone, untimed setup followed by repeatable production-library loading measurements. */
public final class ProjectLoadingProbe {
    private interface Operation { void run() throws Exception; }

    static JSONObject measure(Operation operation) throws Exception {
        operation.run(); // Warm class loading/JIT and the filesystem before measured repetitions.
        JSONArray samples = new JSONArray();
        double[] times = new double[3];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            operation.run();
            times[i] = (System.nanoTime() - start) / 1e6;
            samples.put(times[i]);
        }
        Arrays.sort(times);
        return new JSONObject().put("milliseconds", samples).put("median_ms", times[times.length / 2]);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 4) {
            throw new IllegalArgumentException("Usage: ProjectLoadingProbe LIBRARY OUTPUT.json [DESULTORY.zip MICROLOGY.zip]");
        }
        File root = new File(args[0]);
        ProjectLibrary library = new ProjectLibrary(root);
        if (args.length == 4) {
            if (root.exists()) throw new IllegalArgumentException("Use an empty benchmark directory");
            for (int i = 2; i < args.length; i++) {
                String id = "chart-" + (i - 2);
                try (InputStream input = new FileInputStream(args[i])) {
                    ChartPackage pack = new PackageImporter().importPackage(input,
                            library.workspaceForNewProject(id), new File(args[i]).getName());
                    library.addImportedProject(id, pack, i);
                }
            }
            // A resource-heavy package is a separate case; charts/music stay identical.
            File assets = new File(library.getProjectWorkspace("chart-0"), "tiles");
            Files.createDirectories(assets.toPath());
            byte[] tile = new byte[4096];
            new Random(123).nextBytes(tile);
            tile[0] = (byte) 0x89;
            for (int i = 0; i < 600; i++) {
                Files.write(new File(assets, "image-" + i + ".bin").toPath(), tile);
            }
        }
        JSONArray projects = new JSONArray();
        JSONObject result = new JSONObject();
        for (ProjectRecord record : library.load().getProjects()) {
            File workspace = library.getProjectWorkspace(record.getId());
            ChartPackage initial = new PackageWorkspaceLoader().load(workspace, record.getSourceDisplayName());
            int notes = initial.getChart().totalNotes(), fields = initial.getChart().noiseFields.size();
            JSONObject value = measure(() -> {
                ChartPackage loaded = new PackageWorkspaceLoader().load(workspace, record.getSourceDisplayName());
                if (loaded.getChart().totalNotes() != notes || loaded.getChart().noiseFields.size() != fields) {
                    throw new AssertionError("Chart changed");
                }
            });
            value.put("id", record.getId()).put("name", record.getName()).put("notes", notes)
                    .put("noise_fields", fields).put("entries", initial.getEntries().size());
            projects.put(value);
            System.out.println(record.getName() + " open: " + value.getDouble("median_ms") + " ms");
        }
        result.put("workspace_load", projects);
        result.put("library_scan", measure(() -> {
            ProjectLibrary.Discovery discovery = library.discoverWorkspaces();
            if (!discovery.getIssues().isEmpty()) throw new AssertionError("Unexpected library issue");
        }));
        ProjectRecord first = library.load().getProjects().get(0);
        result.put("open_project", measure(() -> library.openProject(first.getId(), System.currentTimeMillis())));
        result.put("scope", "Host JVM running production package/project classes, warmed filesystem, "
                + "3 measured repetitions. Not phone UI timing.");
        Files.writeString(Path.of(args[1]), result.toString(2) + "\n");
        System.out.println("Library scan: " + result.getJSONObject("library_scan").getDouble("median_ms") + " ms");
    }
}
