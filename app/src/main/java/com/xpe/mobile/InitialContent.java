package com.xpe.mobile;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.project.ProjectLibrary;

final class InitialContent {
    final ProjectLibrary.OpenResult project;
    final ChartDocument fallbackChart;
    final boolean autosave;
    final Exception libraryWarning;
    final boolean memoryWarning;

    private InitialContent(ProjectLibrary.OpenResult project, ChartDocument fallbackChart,
                           boolean autosave, Exception libraryWarning,
                           boolean memoryWarning) {
        this.project = project;
        this.fallbackChart = fallbackChart;
        this.autosave = autosave;
        this.libraryWarning = libraryWarning;
        this.memoryWarning = memoryWarning;
    }

    static InitialContent project(ProjectLibrary.OpenResult value) {
        return new InitialContent(value, null, false, null, false);
    }

    static InitialContent fallback(ChartDocument chart, boolean autosave,
                                   Exception warning, boolean memoryWarning) {
        return new InitialContent(null, chart, autosave, warning, memoryWarning);
    }
}
