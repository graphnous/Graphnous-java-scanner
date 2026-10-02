package dev.graphnous.scanner.java.maven;

import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code pom.xml} with its local parents, read without Maven: enough to
 * find properties, the coordinates of the project, its dependencies and
 * its source directories.
 * Parents that are not in the repository, such as
 * {@code spring-boot-starter-parent}, are not available, so what they
 * define is unknown.
 */
public final class MavenPom {

    private static final String DEFAULT_PARENT_PATH = "../pom.xml";

    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private static final int MAX_REFERENCE_DEPTH = 10;

    private static final String BUILD_HELPER_PLUGIN = "build-helper-maven-plugin";

    private final Path path;

    /**
     * This pom followed by its parents, nearest first, so a module
     * overrides what it inherits.
     */
    private final List<Element> chain;

    private MavenPom(
        final Path path,
        final List<Element> chain
    ) {
        this.path = path;
        this.chain = chain;
    }

    /**
     * Reads the pom and the parents it has in the repository.
     *
     * @throws IllegalStateException when a pom cannot be read or parsed
     */
    public static MavenPom read(final Path pom) {
        final var chain = new ArrayList<Element>();
        final var visited = new HashSet<Path>();

        var current = Optional.of(pom);

        while (current.isPresent()
            && visited.add(current.get().toAbsolutePath().normalize())) {
            final var currentPom = current.get();
            final var project = parse(currentPom);

            chain.add(project);

            current = child(project, "parent")
                .flatMap(parent -> parentPom(currentPom, parent));
        }

        return new MavenPom(pom, List.copyOf(chain));
    }

    public Path path() {
        return path;
    }

    /**
     * The first of the given properties defined by the pom or, when it
     * defines none, by its nearest parent that does, e.g. the first of
     * {@code maven.compiler.release} and {@code maven.compiler.source}.
     * The value is not resolved; see {@link #resolve}.
     */
    public Optional<String> firstProperty(final List<String> names) {
        return chain.stream()
            .map(MavenPom::properties)
            .map(properties -> properties.entrySet()
                .stream()
                .filter(property -> names.contains(property.getKey()))
                .map(Map.Entry::getValue)
                .findFirst())
            .flatMap(Optional::stream)
            .findFirst();
    }

    /**
     * Replaces {@code ${name}} references with the value of the nearest
     * pom defining that property, e.g. {@code ${java.version}} → {@code 25},
     * or with the coordinates of this project, e.g. {@code ${project.version}}.
     *
     * @throws IllegalStateException when a reference cannot be resolved
     */
    public String resolve(final String value) {
        var resolved = value;

        for (int depth = 0; depth < MAX_REFERENCE_DEPTH; depth++) {
            final var matcher = PROPERTY_REFERENCE.matcher(resolved);

            if (!matcher.find()) {
                return resolved;
            }

            resolved = matcher.replaceAll(reference -> Matcher.quoteReplacement(
                lookup(reference.group(1))
                    .orElseThrow(() -> new IllegalStateException(
                        "Could not resolve " + reference.group() + " in " + path
                    ))
            ));
        }

        throw new IllegalStateException(
            "Could not resolve " + value + " in " + path + ": references are nested too deeply"
        );
    }

    public Optional<String> groupId() {
        return inherited("groupId");
    }

    public Optional<String> artifactId() {
        return text(chain.getFirst(), "artifactId").map(this::resolveOrKeep);
    }

    public Optional<String> version() {
        return inherited("version");
    }

    /**
     * The dependencies of the project, including those it inherits from
     * its parents. A dependency without a version gets the version of the
     * nearest {@code dependencyManagement} that has one, and without a
     * scope the managed scope or {@code compile}. Versions that cannot be
     * resolved, such as those managed by a BOM or a parent outside the
     * repository, are left out.
     */
    public List<MavenDependency> dependencies() {
        final var dependencies = new LinkedHashMap<String, MavenDependency>();

        chain.forEach(project -> child(project, "dependencies")
            .map(element -> children(element, "dependency"))
            .orElseGet(List::of)
            .forEach(element -> {
                final var groupId = text(element, "groupId").map(this::resolveOrKeep).orElse("");
                final var artifactId = text(element, "artifactId").map(this::resolveOrKeep).orElse("");

                // The nearest pom declaring a dependency wins
                dependencies.computeIfAbsent(groupId + ":" + artifactId, key -> {
                    final var managed = managed(groupId, artifactId);

                    return new MavenDependency(
                        groupId,
                        artifactId,
                        text(element, "version")
                            .or(() -> managed.flatMap(dependency -> text(dependency, "version")))
                            .flatMap(this::tryResolve)
                            .orElse(null),
                        text(element, "scope")
                            .or(() -> managed.flatMap(dependency -> text(dependency, "scope")))
                            .map(this::resolveOrKeep)
                            .orElse("compile")
                    );
                });
            }));

        return List.copyOf(dependencies.values());
    }

    /**
     * The directories of the production sources, when the pom configures
     * them: its {@code sourceDirectory}, inherited from the nearest pom
     * that sets one, and the directories added with the
     * {@code add-source} goal of the {@code build-helper-maven-plugin}.
     * Relative to the directory of this pom, as Maven resolves them.
     * Without any, Maven uses {@code src/main/java}. Directories with
     * references that cannot be resolved are left out.
     */
    public List<Path> sourceDirectories() {
        return sourceDirectories("sourceDirectory", "add-source");
    }

    /**
     * The directories of the test sources, like {@link #sourceDirectories}
     * but from {@code testSourceDirectory} and the {@code add-test-source}
     * goal. Without any, Maven uses {@code src/test/java}.
     */
    public List<Path> testSourceDirectories() {
        return sourceDirectories("testSourceDirectory", "add-test-source");
    }

    private List<Path> sourceDirectories(
        final String element,
        final String buildHelperGoal
    ) {
        final var directories = new ArrayList<String>();

        chain.stream()
            .map(project -> child(project, "build").flatMap(build -> text(build, element)))
            .flatMap(Optional::stream)
            .findFirst()
            .ifPresent(directories::add);

        chain.stream()
            .flatMap(project -> buildHelperSources(project, buildHelperGoal).stream())
            .forEach(directories::add);

        final var base = path.toAbsolutePath().getParent();

        return directories.stream()
            .map(this::tryResolve)
            .flatMap(Optional::stream)
            .map(directory -> base.resolve(directory).normalize())
            .distinct()
            .toList();
    }

    /**
     * The {@code sources} of the executions of the build helper plugin in
     * the project that run the goal.
     */
    private static List<String> buildHelperSources(
        final Element project,
        final String goal
    ) {
        return child(project, "build")
            .flatMap(build -> child(build, "plugins"))
            .map(plugins -> children(plugins, "plugin"))
            .orElseGet(List::of)
            .stream()
            .filter(plugin -> text(plugin, "artifactId").orElse("").equals(BUILD_HELPER_PLUGIN))
            .flatMap(plugin -> child(plugin, "executions")
                .map(executions -> children(executions, "execution"))
                .orElseGet(List::of)
                .stream())
            .filter(execution -> child(execution, "goals")
                .map(goals -> children(goals, "goal"))
                .orElseGet(List::of)
                .stream()
                .anyMatch(element -> element.getTextContent().trim().equals(goal)))
            .flatMap(execution -> child(execution, "configuration")
                .flatMap(configuration -> child(configuration, "sources"))
                .map(sources -> children(sources, "source"))
                .orElseGet(List::of)
                .stream())
            .map(source -> source.getTextContent().trim())
            .filter(source -> !source.isEmpty())
            .toList();
    }

    private Optional<Element> managed(
        final String groupId,
        final String artifactId
    ) {
        return chain.stream()
            .map(project -> child(project, "dependencyManagement")
                .flatMap(management -> child(management, "dependencies"))
                .map(element -> children(element, "dependency"))
                .orElseGet(List::of))
            .flatMap(List::stream)
            .filter(dependency -> text(dependency, "groupId").map(this::resolveOrKeep).orElse("").equals(groupId)
                && text(dependency, "artifactId").map(this::resolveOrKeep).orElse("").equals(artifactId))
            .findFirst();
    }

    /**
     * The coordinate of the project, or of its parent when the project
     * does not declare it, as Maven inherits the group and version.
     */
    private Optional<String> inherited(final String name) {
        final var project = chain.getFirst();

        return text(project, name)
            .or(() -> child(project, "parent").flatMap(parent -> text(parent, name)))
            .map(this::resolveOrKeep);
    }

    private Optional<String> lookup(final String name) {
        final var property = chain.stream()
            .map(project -> properties(project).get(name))
            .filter(Objects::nonNull)
            .findFirst();

        if (property.isPresent()) {
            return property;
        }

        final Function<String, Optional<String>> parent = coordinate -> child(chain.getFirst(), "parent")
            .flatMap(element -> text(element, coordinate));

        return switch (name) {
            case "project.groupId", "pom.groupId" -> inheritedRaw("groupId");
            case "project.artifactId", "pom.artifactId" -> text(chain.getFirst(), "artifactId");
            case "project.version", "pom.version" -> inheritedRaw("version");
            case "project.parent.groupId" -> parent.apply("groupId");
            case "project.parent.version" -> parent.apply("version");
            case "project.basedir", "basedir" -> Optional.of(path.toAbsolutePath().getParent().toString());
            default -> Optional.empty();
        };
    }

    private Optional<String> inheritedRaw(final String name) {
        final var project = chain.getFirst();

        return text(project, name)
            .or(() -> child(project, "parent").flatMap(parent -> text(parent, name)));
    }

    private Optional<String> tryResolve(final String value) {
        try {
            return Optional.of(resolve(value));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    private String resolveOrKeep(final String value) {
        return tryResolve(value).orElse(value);
    }

    private static Element parse(final Path pom) {
        try (final var input = Files.newInputStream(pom)) {
            return DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(input)
                .getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + pom, e);
        }
    }

    private static Map<String, String> properties(final Element project) {
        final var values = new LinkedHashMap<String, String>();

        child(project, "properties").ifPresent(properties -> {
            final var children = properties.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element property) {
                    values.put(property.getTagName(), property.getTextContent().trim());
                }
            }
        });

        return values;
    }

    /**
     * Resolves the parent pom the way Maven does: {@code relativePath}
     * defaults to {@code ../pom.xml}, an empty value disables the local
     * lookup, and a directory implies its {@code pom.xml}.
     */
    private static Optional<Path> parentPom(
        final Path pom,
        final Element parent
    ) {
        final var relativePath = child(parent, "relativePath")
            .map(element -> element.getTextContent().trim())
            .orElse(DEFAULT_PARENT_PATH);

        if (relativePath.isEmpty()) {
            return Optional.empty();
        }

        var parentPom = pom.getParent().resolve(relativePath).normalize();

        if (Files.isDirectory(parentPom)) {
            parentPom = parentPom.resolve("pom.xml");
        }

        return Files.isRegularFile(parentPom)
            ? Optional.of(parentPom)
            : Optional.empty();
    }

    private static Optional<String> text(
        final Element element,
        final String name
    ) {
        return child(element, name)
            .map(child -> child.getTextContent().trim())
            .filter(text -> !text.isEmpty());
    }

    private static Optional<Element> child(
        final Element element,
        final String name
    ) {
        return children(element, name).stream().findFirst();
    }

    private static List<Element> children(
        final Element element,
        final String name
    ) {
        final var matches = new ArrayList<Element>();
        final var children = element.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                && name.equals(child.getTagName())) {
                matches.add(child);
            }
        }

        return matches;
    }
}
