package dev.graphnous.scanner;

import dev.graphnous.scanner.model.File;
import dev.graphnous.scanner.model.ScanTarget;
import dev.graphnous.scanner.model.Module;

import java.nio.file.Path;
import java.util.List;

public interface FileDetector {

    List<File> detectFiles(final Path path, final ScanTarget target, final Module module);

}