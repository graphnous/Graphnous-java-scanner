package dev.graphnous.scanner;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The types declared by the sources of a scan target and the packages
 * they are in, so a type used in one file can be resolved to a type
 * declared in another.
 */
public final class JavaTypeIndex {

    static final JavaTypeIndex EMPTY = new JavaTypeIndex(Set.of(), Set.of(), List.of(), List.of(), ParserConfiguration.LanguageLevel.CURRENT);

    /**
     * The number of parsed source files each type solver keeps, to bound
     * the memory a large scan target takes.
     */
    private static final long PARSED_FILES = 2_000;

    private final Set<String> types;

    private final Set<String> packages;

    /**
     * A type solver per thread, as files are parsed in parallel and the
     * caches of a {@link JavaParserTypeSolver} are not thread-safe.
     */
    private final ThreadLocal<TypeSolver> typeSolver;

    private final boolean hasLibraries;

    /**
     * @param types               qualified names of the types, including
     *                            nested types, e.g. {@code com.example.Order.Line}
     * @param packages            names of the packages the types are in
     * @param sourceRoots         the directories the packages of the sources
     *                            start in, e.g. {@code src/main/java}
     * @param libraries           the jars of the libraries the sources use,
     *                            and directories of sources the build
     *                            generates, e.g. {@code target/generated-sources/openapi}
     * @param languageLevel       the Java version the sources are parsed
     *                            with when a type is looked up in them
     */
    JavaTypeIndex(
        final Collection<String> types,
        final Collection<String> packages,
        final Collection<Path> sourceRoots,
        final Collection<Path> libraries,
        final ParserConfiguration.LanguageLevel languageLevel
    ) {
        this.types = Set.copyOf(types);
        this.packages = Set.copyOf(packages);

        final var roots = List.copyOf(Set.copyOf(sourceRoots));
        final var classpath = List.copyOf(new LinkedHashSet<>(libraries));

        this.hasLibraries = !classpath.isEmpty();

        this.typeSolver = ThreadLocal.withInitial(() -> {
            // The JDK the scanner runs on, without the libraries of the
            // scanner itself
            final var solver = new CombinedTypeSolver(new ClassLoaderTypeSolver(ClassLoader.getPlatformClassLoader()));

            // The sources the solver parses resolve with the solver as well
            final var configuration = new ParserConfiguration()
                .setLanguageLevel(languageLevel)
                .setSymbolResolver(new JavaSymbolSolver(solver));

            roots.forEach(root -> solver.add(new JavaParserTypeSolver(root, configuration, PARSED_FILES)));

            // After the sources, so a scanned type wins over a jar of it
            classpath.forEach(library -> {
                if (Files.isDirectory(library)) {
                    solver.add(new JavaParserTypeSolver(library, configuration, PARSED_FILES));
                } else {
                    jarTypeSolver(library).ifPresent(solver::add);
                }
            });

            return solver;
        });
    }

    /**
     * A jar that cannot be read, e.g. a corrupt download, is left out.
     */
    private static Optional<TypeSolver> jarTypeSolver(final Path jar) {
        try {
            return Optional.of(new JarTypeSolver(jar));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    boolean containsType(final String qualifiedName) {
        return types.contains(qualifiedName);
    }

    boolean containsPackage(final String packageName) {
        return packages.contains(packageName);
    }

    /**
     * Whether the type solver has the type, which is how types of the
     * libraries are known; without libraries, nothing more is known than
     * the scanned types and the JDK.
     */
    boolean containsLibraryType(final String qualifiedName) {
        if (!hasLibraries) {
            return false;
        }

        try {
            return typeSolver().tryToSolveType(qualifiedName).isSolved();
        } catch (RuntimeException e) {
            return false;
        }
    }

    int size() {
        return types.size();
    }

    /**
     * Looks types up in the JDK and the sources of the scan target, to
     * resolve the methods and fields that method bodies use.
     */
    TypeSolver typeSolver() {
        return typeSolver.get();
    }
}
