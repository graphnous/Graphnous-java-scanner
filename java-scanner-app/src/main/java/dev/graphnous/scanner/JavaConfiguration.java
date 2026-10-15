package dev.graphnous.scanner;

import dev.graphnous.scanner.model.Annotation;
import dev.graphnous.scanner.model.Call;
import dev.graphnous.scanner.model.Class;
import dev.graphnous.scanner.model.ConfigurationProperty;
import dev.graphnous.scanner.model.Method;
import dev.graphnous.scanner.model.Module;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The configuration properties of Spring Boot modules that their code
 * refers to or that name what they connect to, from the
 * {@code application} and {@code bootstrap} properties and YAML files in
 * {@code src/main/resources} and its {@code config} directory, with those
 * of each profile.
 * <p>
 * A property is kept when:
 * <ul>
 *     <li>the code of any module of the target refers to its key, in a
 *     {@code ${...}} placeholder of an annotation or call argument, such as
 *     {@code @Value("${orders.topic}")}, or by passing it to
 *     {@code getProperty}; a key also refers to the keys below it, as
 *     {@code app.topics} to {@code app.topics[0]};</li>
 *     <li>it is under the prefix of a {@code @ConfigurationProperties}
 *     class or method;</li>
 *     <li>a property kept refers to it, as {@code orders.topic=${app.prefix}.orders}
 *     refers to {@code app.prefix};</li>
 *     <li>it is one of {@link #FRAMEWORK_KEYS}.</li>
 * </ul>
 * Never a secret: a property whose key names one, or whose value holds
 * one, see {@link Secrets}, is left out whatever refers to it. Keys are
 * compared as Spring binds them, so {@code orders.topic-name} is
 * {@code orders.topicName}.
 */
final class JavaConfiguration {

    /**
     * The keys Spring reads that name a destination or a path, with
     * {@code *} for any one part, such as the name of a binding.
     */
    static final List<String> FRAMEWORK_KEYS = List.of(
        "spring.application.name",
        "server.servlet.context-path",
        "spring.mvc.servlet.path",
        "spring.webflux.base-path",
        "spring.kafka.template.default-topic",
        "spring.kafka.consumer.group-id",
        "spring.rabbitmq.template.exchange",
        "spring.rabbitmq.template.routing-key",
        "spring.rabbitmq.template.default-receive-queue",
        "spring.jms.template.default-destination",
        "spring.cloud.stream.function.definition",
        "spring.cloud.stream.bindings.*.destination",
        "spring.cloud.stream.bindings.*.group"
    );

    private static final Pattern FILE = Pattern.compile("(?:application|bootstrap)(?:-(.+))?\\.(?:properties|ya?ml)");

    /**
     * The keys that give the profile of a document of a file.
     */
    private static final List<String> PROFILE_KEYS = List.of("spring.config.activate.on-profile", "spring.profiles");

    /**
     * Calls that read a property by its key, as Spring's
     * {@code Environment.getProperty("orders.topic")}.
     */
    private static final Pattern PROPERTY_READ = Pattern.compile("(?:^|\\.)(?:getProperty|getRequiredProperty|containsProperty)(?:\\(.*)?$");

    /**
     * The keys the code refers to, as {@link #canonical}.
     */
    private final Set<String> keys;

    /**
     * The prefixes of {@code @ConfigurationProperties}, as
     * {@link #canonical}.
     */
    private final Set<String> prefixes;

    private final ScanLog log;

    private JavaConfiguration(
        final Set<String> keys,
        final Set<String> prefixes,
        final ScanLog log
    ) {
        this.keys = keys;
        this.prefixes = prefixes;
        this.log = log;
    }

    /**
     * What the code of the modules refers to: a library module may read
     * properties an application module sets.
     */
    static JavaConfiguration of(
        final List<Module> modules,
        final ScanLog log
    ) {
        final var keys = new HashSet<String>();
        final var prefixes = new HashSet<String>();

        modules.stream()
            .flatMap(module -> module.getFiles().stream())
            .flatMap(file -> file.getClasses().stream())
            .forEach(clazz -> references(clazz, key -> keys.add(canonical(key)), prefix -> prefixes.add(canonical(prefix))));

        return new JavaConfiguration(keys, prefixes, log);
    }

    /**
     * The properties of the module that are kept, in the order of their
     * files and lines; null when the module has no configuration files.
     *
     * @param moduleDirectory the directory of the module
     * @param targetDirectory the directory of the target, which the paths
     *                        of the files are relative to
     */
    List<ConfigurationProperty> read(
        final Path moduleDirectory,
        final Path targetDirectory
    ) {
        final var files = files(moduleDirectory);

        if (files.isEmpty()) {
            return null;
        }

        final var properties = new ArrayList<ConfigurationProperty>();

        files.forEach(file -> {
            final var name = file.getFileName().toString();
            final var profile = profile(file);
            final var path = targetDirectory.relativize(file).toString().replace('\\', '/');

            try {
                final var text = Files.readString(file, StandardCharsets.UTF_8);
                final var documents = name.endsWith(".properties") ? propertiesDocuments(text) : yamlDocuments(text);

                documents.forEach(document -> {
                    final var documentProfile = document.stream()
                        .filter(property -> PROFILE_KEYS.contains(property.getKey()))
                        .map(ConfigurationProperty::getValue)
                        .findFirst()
                        .orElse(profile);

                    document.forEach(property -> {
                        property.setFile(path);
                        property.setProfile(documentProfile);
                        properties.add(property);
                    });
                });
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping configuration file " + path + ": " + e.getMessage());
            }
        });

        return kept(properties);
    }

    /**
     * The properties that are referred to, see the class, and those they
     * refer to in turn.
     */
    private List<ConfigurationProperty> kept(final List<ConfigurationProperty> properties) {
        final var referred = new HashSet<>(keys);
        final Set<ConfigurationProperty> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        final var candidates = properties.stream()
            .filter(property -> !Secrets.namesSecret(property.getKey()) && !Secrets.holdsSecret(property.getValue()))
            .toList();

        var found = true;

        while (found) {
            found = false;

            for (final var property : candidates) {
                if (!kept.contains(property) && isReferred(canonical(property.getKey()), referred)) {
                    kept.add(property);
                    placeholders(property.getValue(), key -> referred.add(canonical(key)));
                    found = true;
                }
            }
        }

        return properties.stream()
            .filter(kept::contains)
            .toList();
    }

    private boolean isReferred(
        final String key,
        final Set<String> referred
    ) {
        return referred.stream().anyMatch(reference -> isAtOrBelow(key, reference))
            || prefixes.stream().anyMatch(prefix -> !key.equals(prefix) && isAtOrBelow(key, prefix))
            || FRAMEWORK_KEYS.stream().anyMatch(pattern -> matches(key, canonical(pattern)));
    }

    private static boolean isAtOrBelow(
        final String key,
        final String reference
    ) {
        return key.equals(reference)
            || key.startsWith(reference) && (key.charAt(reference.length()) == '.' || key.charAt(reference.length()) == '[');
    }

    private static boolean matches(
        final String key,
        final String pattern
    ) {
        final var keyParts = key.split("\\.");
        final var patternParts = pattern.split("\\.");

        if (keyParts.length != patternParts.length) {
            return false;
        }

        for (int i = 0; i < keyParts.length; i++) {
            if (!patternParts[i].equals("*") && !patternParts[i].equals(keyParts[i])) {
                return false;
            }
        }

        return true;
    }

    /**
     * A key as Spring binds it: in lower case, without {@code -} and
     * {@code _}.
     */
    static String canonical(final String key) {
        return key.strip().toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
    }

    /**
     * The configuration files of the module: those in
     * {@code src/main/resources}, then those in its {@code config}
     * directory, which override them; in each, the files without a profile
     * first, then those of profiles, by name.
     */
    private static List<Path> files(final Path moduleDirectory) {
        final var resources = moduleDirectory.resolve("src").resolve("main").resolve("resources");

        return Stream.of(resources, resources.resolve("config"))
            .filter(Files::isDirectory)
            .flatMap(directory -> {
                try (var entries = Files.list(directory)) {
                    return entries
                        .filter(Files::isRegularFile)
                        .filter(file -> FILE.matcher(file.getFileName().toString()).matches())
                        .sorted(Comparator
                            .comparing((Path file) -> profile(file) != null)
                            .thenComparing(file -> file.getFileName().toString()))
                        .toList()
                        .stream();
                } catch (IOException e) {
                    return Stream.empty();
                }
            })
            .toList();
    }

    /**
     * The profile of a configuration file, from its name, e.g.
     * {@code prod} for {@code application-prod.yml}; null for none.
     */
    private static String profile(final Path file) {
        final var matcher = FILE.matcher(file.getFileName().toString());

        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * The keys the class and the classes in it refer to, and the prefixes
     * of their {@code @ConfigurationProperties}.
     */
    private static void references(
        final Class clazz,
        final Consumer<String> keys,
        final Consumer<String> prefixes
    ) {
        annotations(clazz.getAnnotations(), keys, prefixes);
        clazz.getFields().forEach(field -> annotations(field.getAnnotations(), keys, prefixes));
        clazz.getRecordComponents().forEach(component -> annotations(component.getAnnotations(), keys, prefixes));
        clazz.getEnumConstants().forEach(constant -> annotations(constant.getAnnotations(), keys, prefixes));

        clazz.getMethods().forEach(method -> {
            annotations(method.getAnnotations(), keys, prefixes);
            method.getParameters().forEach(parameter -> annotations(parameter.getAnnotations(), keys, prefixes));
            calls(method, keys);
        });

        clazz.getClasses().forEach(nested -> references(nested, keys, prefixes));
    }

    private static void annotations(
        final List<Annotation> annotations,
        final Consumer<String> keys,
        final Consumer<String> prefixes
    ) {
        annotations.forEach(annotation -> {
            final var arguments = annotation.getArguments() == null
                ? Map.<String, Object>of()
                : annotation.getArguments().getAdditionalProperties();

            if ("ConfigurationProperties".equals(annotation.getName())) {
                Stream.of(arguments.get("prefix"), arguments.get("value"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(prefix -> !prefix.isBlank())
                    .forEach(prefixes);
            }

            arguments.values().forEach(value -> argument(value, keys, prefixes));
        });
    }

    /**
     * The placeholders in an annotation argument: in its lists and nested
     * annotations too.
     */
    private static void argument(
        final Object value,
        final Consumer<String> keys,
        final Consumer<String> prefixes
    ) {
        switch (value) {
            case String text -> placeholders(text, keys);
            case List<?> list -> list.forEach(item -> argument(item, keys, prefixes));
            case Annotation annotation -> annotations(List.of(annotation), keys, prefixes);
            case null, default -> {
            }
        }
    }

    private static void calls(
        final Method method,
        final Consumer<String> keys
    ) {
        for (final Call call : method.getCalls()) {
            final var arguments = call.getArguments();

            arguments.forEach(argument -> {
                if (argument.getConstant() != null) {
                    placeholders(argument.getConstant(), keys);
                }
            });

            if (!arguments.isEmpty() && arguments.getFirst().getConstant() != null && PROPERTY_READ.matcher(call.getTarget()).find()) {
                keys.accept(arguments.getFirst().getConstant());
            }
        }
    }

    /**
     * The keys of the {@code ${key}} and {@code ${key:default}}
     * placeholders in the text, and of those in their defaults.
     */
    static void placeholders(
        final String text,
        final Consumer<String> keys
    ) {
        var start = text.indexOf("${");

        while (start >= 0) {
            final var end = closing(text, start + 2);

            if (end < 0) {
                return;
            }

            final var inner = text.substring(start + 2, end);
            final var colon = separator(inner);
            final var key = (colon < 0 ? inner : inner.substring(0, colon)).strip();

            if (!key.isEmpty() && !key.contains("${")) {
                keys.accept(key);
            }

            placeholders(colon < 0 ? key : inner.substring(colon + 1), keys);

            start = text.indexOf("${", end + 1);
        }
    }

    /**
     * The index of the brace that closes a placeholder whose key starts at
     * the index; -1 when none does.
     */
    private static int closing(
        final String text,
        final int start
    ) {
        var depth = 1;

        for (int i = start; i < text.length(); i++) {
            if (text.startsWith("${", i)) {
                depth++;
                i++;
            } else if (text.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }

        return -1;
    }

    /**
     * The index of the colon between the key and the default of a
     * placeholder, outside nested placeholders; -1 when there is none.
     */
    private static int separator(final String inner) {
        var depth = 0;

        for (int i = 0; i < inner.length(); i++) {
            if (inner.startsWith("${", i)) {
                depth++;
                i++;
            } else if (inner.charAt(i) == '}') {
                depth--;
            } else if (inner.charAt(i) == ':' && depth == 0) {
                return i;
            }
        }

        return -1;
    }

    /**
     * The documents of a properties file, which Spring separates by a
     * line {@code #---} or {@code !---}, each with its properties.
     */
    static List<List<ConfigurationProperty>> propertiesDocuments(final String text) {
        final var documents = new ArrayList<List<ConfigurationProperty>>();
        var document = new ArrayList<ConfigurationProperty>();
        documents.add(document);

        final var lines = text.split("\\r?\\n|\\r", -1);

        for (int i = 0; i < lines.length; i++) {
            final var line = lines[i].stripLeading();

            if (line.equals("#---") || line.equals("!---")) {
                document = new ArrayList<>();
                documents.add(document);
                continue;
            }

            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }

            // A line ending in an odd number of backslashes goes on
            final var logical = new StringBuilder(line);
            final var number = i + 1;

            while (endsInContinuation(logical) && i + 1 < lines.length) {
                logical.setLength(logical.length() - 1);
                logical.append(lines[++i].stripLeading());
            }

            final var property = property(logical.toString());
            property.setLine(number);
            document.add(property);
        }

        return documents.stream().filter(properties -> !properties.isEmpty()).toList();
    }

    private static boolean endsInContinuation(final CharSequence line) {
        var backslashes = 0;

        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }

        return backslashes % 2 == 1;
    }

    /**
     * The key and value of a line of a properties file: the key ends at
     * the first {@code =}, {@code :} or whitespace that is not escaped.
     */
    private static ConfigurationProperty property(final String line) {
        var end = 0;

        while (end < line.length()) {
            final var c = line.charAt(end);

            if (c == '\\') {
                end += 2;
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                end++;
            }
        }

        end = Math.min(end, line.length());

        var valueStart = end;

        while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart < line.length() && (line.charAt(valueStart) == '=' || line.charAt(valueStart) == ':')) {
            valueStart++;
        }

        while (valueStart < line.length() && Character.isWhitespace(line.charAt(valueStart))) {
            valueStart++;
        }

        final var property = new ConfigurationProperty();
        property.setKey(unescape(line.substring(0, end)));
        property.setValue(unescape(line.substring(valueStart)));

        return property;
    }

    private static String unescape(final String text) {
        final var result = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);

            if (c != '\\' || i + 1 == text.length()) {
                result.append(c);
                continue;
            }

            final var next = text.charAt(++i);

            switch (next) {
                case 't' -> result.append('\t');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 'f' -> result.append('\f');
                case 'u' -> {
                    if (i + 4 < text.length() && text.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
                        result.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        result.append('u');
                    }
                }
                default -> result.append(next);
            }
        }

        return result.toString();
    }

    /**
     * The documents of a YAML file, each with its properties: the keys of
     * nested maps joined by dots, the items of lists as {@code [index]}.
     */
    static List<List<ConfigurationProperty>> yamlDocuments(final String text) {
        final var options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);

        final var documents = new ArrayList<List<ConfigurationProperty>>();

        for (final var root : new Yaml(options).composeAll(new StringReader(text))) {
            final var document = new ArrayList<ConfigurationProperty>();

            if (root != null) {
                flatten(root, "", document);
            }

            if (!document.isEmpty()) {
                documents.add(document);
            }
        }

        return documents;
    }

    private static void flatten(
        final Node node,
        final String key,
        final List<ConfigurationProperty> properties
    ) {
        switch (node) {
            case MappingNode mapping -> mapping.getValue().forEach(tuple -> {
                if (tuple.getKeyNode() instanceof ScalarNode name && !name.getValue().equals("<<")) {
                    flatten(tuple.getValueNode(), key.isEmpty() ? name.getValue() : key + "." + name.getValue(), properties);
                }
            });
            case SequenceNode sequence -> {
                final var items = sequence.getValue();

                for (int i = 0; i < items.size(); i++) {
                    flatten(items.get(i), key + "[" + i + "]", properties);
                }
            }
            case ScalarNode scalar when !key.isEmpty() -> {
                final var property = new ConfigurationProperty();
                property.setKey(key);
                property.setValue(scalar.getTag().equals(Tag.NULL) ? "" : scalar.getValue());
                property.setLine(scalar.getStartMark().getLine() + 1);
                properties.add(property);
            }
            default -> {
            }
        }
    }
}
