package dev.graphnous.scanner;

import dev.graphnous.core.model.Module;
import dev.graphnous.core.model.ScanTarget;

import java.nio.file.Path;
import java.util.List;

public interface ModuleDetector {

    List<Module> detectModules(final Path path, final ScanTarget target);

    ScanTarget.BuildSystem forSystem();
}
