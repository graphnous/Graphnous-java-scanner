package dev.graphnous.scanner;

import dev.graphnous.core.model.File;
import dev.graphnous.core.model.ScanTarget;
import dev.graphnous.core.model.Module;

import java.nio.file.Path;
import java.util.List;

public interface FileDetector {

    List<File> detectFiles(final Path path, final ScanTarget target, final Module module);

}