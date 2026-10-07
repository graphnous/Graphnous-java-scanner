package dev.graphnous.scanner;

import dev.graphnous.core.model.ScanResult;
import dev.graphnous.core.model.ScanTarget;

import java.nio.file.Path;

public interface Scanner {

    ScanResult scan(Path path, ScanTarget target);

}