package dev.graphnous.scanner;

import dev.graphnous.scanner.model.Module;
import dev.graphnous.scanner.model.ScanTarget;

import java.nio.file.Path;
import java.util.List;

public interface ModuleDetector {

    List<Module> detectModules(final Path path, final ScanTarget target);

    ScanTarget.BuildSystem forSystem();
}
