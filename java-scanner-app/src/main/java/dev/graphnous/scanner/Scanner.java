package dev.graphnous.scanner;

import dev.graphnous.scanner.model.ScanResultSchema;
import dev.graphnous.scanner.model.ScanTarget;

import java.nio.file.Path;

public interface Scanner {

    ScanResultSchema scan(Path path, ScanTarget target);

}