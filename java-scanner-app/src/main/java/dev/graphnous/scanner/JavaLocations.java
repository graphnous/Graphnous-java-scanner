package dev.graphnous.scanner;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import dev.graphnous.scanner.model.Annotation;
import dev.graphnous.scanner.model.Call;
import dev.graphnous.scanner.model.Class;
import dev.graphnous.scanner.model.EnumConstant;
import dev.graphnous.scanner.model.Field;
import dev.graphnous.scanner.model.FieldAccess;
import dev.graphnous.scanner.model.Import;
import dev.graphnous.scanner.model.Method;
import dev.graphnous.scanner.model.Parameter;
import dev.graphnous.scanner.model.RecordComponent;
import dev.graphnous.scanner.model.TypeUse;

import java.util.function.Consumer;

/**
 * Sets where an element of the scan result is in its source file: the
 * lines and columns it starts and ends at, starting at 1 and inclusive,
 * as JavaParser counts them. A declaration includes its annotations and
 * its body, but not its Javadoc.
 */
final class JavaLocations {

    private JavaLocations() {
    }

    static void locate(final Node node, final Class element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final Method element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final Parameter element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final Field element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final EnumConstant element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final RecordComponent element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final Annotation element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final Import element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final Call element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final FieldAccess element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    static void locate(final Node node, final TypeUse element) {
        range(node, range -> {
            element.setStartLine(range.begin.line);
            element.setEndLine(range.end.line);
            element.setStartColumn(range.begin.column);
            element.setEndColumn(range.end.column);
        });
    }

    /**
     * Nodes parsed from source always have a range; nodes created by
     * JavaParser itself have none, and their element no location.
     */
    private static void range(final Node node, final Consumer<Range> setter) {
        node.getRange().ifPresent(setter);
    }
}
