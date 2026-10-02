package dev.graphnous.scanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects what the scanner reports, per level.
 */
class RecordingScanLog implements ScanLog {

    final List<String> info = new ArrayList<>();
    final List<String> details = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    @Override
    public void info(final String line) {
        info.add(line);
    }

    @Override
    public void detail(final String line) {
        details.add(line);
    }

    @Override
    public void warn(final String line) {
        warnings.add(line);
    }
}
