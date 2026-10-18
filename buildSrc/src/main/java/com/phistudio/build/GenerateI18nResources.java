package com.phistudio.build;

import java.io.File;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

/** One isolated output directory per Android variant. */
@CacheableTask
public abstract class GenerateI18nResources extends DefaultTask {
    public GenerateI18nResources() { getApplicationNameOverride().convention(""); }
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getLanguageDirectory();
    @Input public abstract Property<String> getApplicationNameOverride();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    @TaskAction public void generate() throws Exception {
        File output = getOutputDirectory().get().getAsFile();
        // Validate before replacing the previous output.
        I18nGenerator generator = new I18nGenerator(getLanguageDirectory().get().getAsFile().toPath(),
                getApplicationNameOverride().get());
        getProject().delete(output);
        generator.generate(output.toPath());
    }
}
