package com.xpe.mobile.app;

import com.xpe.mobile.packageio.ChartPackage;

final class ImportedProject {
    final String id;
    final ChartPackage chartPackage;
    final String name;

    ImportedProject(String id, ChartPackage chartPackage, String name) {
        this.id = id;
        this.chartPackage = chartPackage;
        this.name = name;
    }
}
