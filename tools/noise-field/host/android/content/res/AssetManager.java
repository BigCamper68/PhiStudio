package android.content.res;
import java.io.*;import java.nio.file.*;
/** Host-only file adapter. Never included in the APK. */
public final class AssetManager {private final Path root;public AssetManager(String root){this.root=Path.of(root);}public InputStream open(String name)throws IOException{return Files.newInputStream(root.resolve(name));}}
