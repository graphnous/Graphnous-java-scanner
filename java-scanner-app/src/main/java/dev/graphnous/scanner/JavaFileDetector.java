package dev.graphnous.scanner;

import dev.graphnous.core.model.Class;
import dev.graphnous.core.model.File;
import dev.graphnous.core.model.Module;
import dev.graphnous.core.model.Package;
import dev.graphnous.core.model.ScanTarget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class JavaFileDetector implements FileDetector {

    /**
     * A directory containing one of these files is a module of its own,
     * so its sources belong to that module rather than to the parent.
     */
    private static final List<String> MODULE_FILES = List.of(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts"
    );

    /**
     * Build output directories of Maven ({@code target}) and Gradle
     * ({@code build}). Only skipped directly below the module root, so
     * source packages with the same name are still scanned.
     */
    private static final List<String> OUTPUT_DIRECTORIES = List.of(
        "target",
        "build"
    );

    private final JavaClassParser classParser;

    private final ScanLog log;

    public JavaFileDetector() {
        this(new JavaClassParser(), ScanLog.console(false));
    }

    public JavaFileDetector(
            final JavaClassParser classParser,
            final ScanLog log
    ) {
        this.classParser = classParser;
        this.log = log;
    }

    @Override
    public List<File> detectFiles(
            final Path repository,
            final ScanTarget target,
            final Module module
    ) {
        return detectFiles(repository, target, module, JavaTypeIndex.EMPTY);
    }

    /**
     * @param index the types of the scan target, to resolve the types the
     *              files use; see {@link #index}
     */
    public List<File> detectFiles(
            final Path repository,
            final ScanTarget target,
            final Module module,
            final JavaTypeIndex index
    ) {
        return parseFiles(repository, target, module, index)
                .stream()
                .map(ParsedFile::file)
                .toList();
    }

    /**
     * Sets the Java files of the module and the packages their classes
     * are in.
     *
     * @param index the types of the scan target, to resolve the types the
     *              files use; see {@link #index}
     */
    public void detect(
            final Path repository,
            final ScanTarget target,
            final Module module,
            final JavaTypeIndex index
    ) {
        final var files = parseFiles(repository, target, module, index);

        module.setFiles(
                files.stream().map(ParsedFile::file).toList()
        );

        module.setPackages(packages(files));
    }

    private List<ParsedFile> parseFiles(
            final Path repository,
            final ScanTarget target,
            final Module module,
            final JavaTypeIndex index
    ) {
        final var targetPath = repository.resolve(target.getPath()).normalize();
        final var layout = SourceLayout.of(targetPath.resolve(module.getPath()).normalize());

        // Parsed in parallel; reported afterwards, in the order of the files
        final var outcomes = findSources(repository, target, module)
                .parallelStream()
                .map(file -> toFile(targetPath, layout, file, index))
                .toList();

        outcomes.forEach(outcome -> {
            if (outcome.file() == null) {
                log.warn(outcome.message());
            } else {
                log.detail(outcome.message());
            }
        });

        return outcomes.stream()
                .map(Outcome::file)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The packages of the files, sorted by name. Their classes are listed
     * under the files, which name their package in {@link File#getPackage}.
     * Classes in the default package are in no package.
     */
    private static List<Package> packages(final List<ParsedFile> files) {
        return files.stream()
                .map(ParsedFile::packageName)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .map(qualifiedName -> {
                    final var javaPackage = new Package();

                    javaPackage.setName(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
                    javaPackage.setQualifiedName(qualifiedName);

                    return javaPackage;
                })
                .toList();
    }

    /**
     * The number of classes, including the classes nested in them.
     */
    private static int count(final List<Class> classes) {
        return classes.stream()
                .mapToInt(clazz -> 1 + count(clazz.getClasses()))
                .sum();
    }

    /**
     * Collects the types declared by the Java files of all modules, so a
     * type used in one module resolves to a type declared in another.
     */
    public JavaTypeIndex index(
            final Path repository,
            final ScanTarget target,
            final List<Module> modules
    ) {
        return classParser.index(
                modules.stream()
                        .flatMap(module -> findSources(repository, target, module).stream())
                        .toList()
        );
    }

    private List<Path> findSources(
            final Path repository,
            final ScanTarget target,
            final Module module
    ) {
        final var modulePath = repository.resolve(target.getPath())
                .resolve(module.getPath())
                .normalize();

        try {
            return findSources(modulePath);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to find Java files in " + modulePath,
                    e
            );
        }
    }

    private List<Path> findSources(final Path modulePath) throws IOException {
        final var sources = new ArrayList<Path>();

        Files.walkFileTree(modulePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    final Path directory,
                    final BasicFileAttributes attributes
            ) {
                if (directory.equals(modulePath)) {
                    return FileVisitResult.CONTINUE;
                }

                return isModule(directory) || isOutput(modulePath, directory)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    final Path file,
                    final BasicFileAttributes attributes
            ) {
                if (attributes.isRegularFile()
                        && file.getFileName().toString().endsWith(".java")) {
                    sources.add(file);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return sources;
    }

    private boolean isModule(final Path directory) {
        return MODULE_FILES.stream()
                .anyMatch(file -> Files.isRegularFile(directory.resolve(file)));
    }

    private boolean isOutput(
            final Path modulePath,
            final Path directory
    ) {
        return modulePath.equals(directory.getParent())
                && OUTPUT_DIRECTORIES.contains(directory.getFileName().toString());
    }

    private static String describe(final JavaParseException exception) {
        final var problems = exception.getProblems();

        if (problems.isEmpty()) {
            return "syntax error";
        }

        // JavaParser appends every token it would have accepted; the position
        // and the unexpected token are what help
        final var first = problems.getFirst()
                .getVerboseMessage()
                .replaceFirst(", expected one of .*", "");

        return problems.size() == 1
            ? first
            : first + " (and " + (problems.size() - 1) + " more)";
    }

    static String classes(final int count) {
        return count + (count == 1 ? " class" : " classes");
    }

    /**
     * Returns the parsed file, or no file when it cannot be read or
     * parsed, so one broken file does not fail the whole scan.
     */
    private Outcome toFile(
            final Path targetPath,
            final SourceLayout layout,
            final Path path,
            final JavaTypeIndex index
    ) {
        final var file = new File();

        file.setPath(
                targetPath.relativize(path).toString()
        );

        file.setLanguage("JAVA");
        file.setSourceSet(layout.sourceSet(path));

        final String packageName;

        try {
            final var content = Files.readAllBytes(path);

            file.setSize(content.length);
            file.setChecksum(sha256(content));

            packageName = classParser.parse(path, file, index);
        } catch (JavaParseException e) {
            return Outcome.skipped("Skipping " + file.getPath() + ": " + describe(e));
        } catch (IOException | UncheckedIOException e) {
            return Outcome.skipped("Skipping " + file.getPath() + ": could not read it (" + e.getMessage() + ")");
        } catch (RuntimeException | StackOverflowError e) {
            // Anything unexpected, e.g. a file nested too deeply, only
            // skips that file
            return Outcome.skipped(
                "Skipping " + file.getPath() + ": could not analyse it (" + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")"
            );
        }

        return new Outcome(
                new ParsedFile(file, packageName),
                "  " + file.getPath() + ": " + classes(count(file.getClasses()))
        );
    }

    /**
     * A parsed file with its detail line, or no file with the warning why.
     */
    private record Outcome(
            ParsedFile file,
            String message
    ) {
        static Outcome skipped(final String warning) {
            return new Outcome(null, warning);
        }
    }

    /**
     * The SHA-256 of the content as lowercase hex, to tell whether a file
     * changed between scans.
     */
    private static String sha256(final byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record ParsedFile(
            File file,
            String packageName
    ) {
    }

}
