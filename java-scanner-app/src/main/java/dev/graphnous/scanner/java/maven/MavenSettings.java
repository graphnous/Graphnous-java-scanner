package dev.graphnous.scanner.java.maven;

import org.w3c.dom.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The remote repositories a Maven {@code settings.xml} configures, to
 * download the libraries of a project from, e.g. a company Nexus:
 * <ul>
 *     <li>the {@code repositories} of the active {@code profiles}, followed
 *     by Maven Central unless a repository has its id, {@code central};</li>
 *     <li>with the {@code mirrors} in place of the repositories they mirror,
 *     e.g. a single mirror of {@code *};</li>
 *     <li>with the credentials of the {@code servers} with the same id: a
 *     {@code username} and {@code password} for basic authentication, or
 *     the {@code httpHeaders} of their {@code configuration}, e.g. a
 *     token.</li>
 * </ul>
 * Values may refer to environment variables as {@code ${env.NAME}}, so the
 * file holds no secrets. Encrypted passwords are not supported.
 * <p>
 * The repositories the poms of the scanned project declare are not used:
 * credentials go to repositories by their id, so a project could name a
 * repository of its own after one of the settings and receive them.
 */
public final class MavenSettings {

    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("\\$\\{env\\.([^}]+)}");

    private MavenSettings() {
    }

    /**
     * The remote repositories to download from, in the order to try them.
     *
     * @param central     where Maven Central is, unless the settings define
     *                    a repository with its id or a mirror of it
     * @param environment the environment variables, by name
     * @throws IllegalStateException when the file cannot be read
     */
    public static List<MavenRemote> remotes(
        final Path settings,
        final URI central,
        final Function<String, String> environment
    ) {
        final Element root;

        try (var input = Files.newInputStream(settings)) {
            root = MavenPom.documentBuilderFactory().newDocumentBuilder().parse(input).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the Maven settings " + settings, e);
        }

        final Function<String, String> value = text -> interpolate(text, environment);

        // id → URL, in order
        final var repositories = new LinkedHashMap<String, String>();
        final var active = activeProfiles(root, value);

        children(root, "profiles", "profile").stream()
            .filter(profile -> active.contains(text(profile, "id").map(value).orElse("")))
            .flatMap(profile -> children(profile, "repositories", "repository").stream())
            .forEach(repository -> text(repository, "url").map(value).ifPresent(
                url -> repositories.putIfAbsent(text(repository, "id").map(value).orElse(url), url)
            ));

        repositories.putIfAbsent("central", central.toString());

        // A repository goes to the first mirror that mirrors it; several
        // repositories of one mirror go to it once
        final var mirrors = children(root, "mirrors", "mirror");
        final var remotes = new LinkedHashMap<String, String>();

        repositories.forEach((id, url) -> {
            final var mirror = mirrors.stream()
                .filter(candidate -> mirrors(text(candidate, "mirrorOf").map(value).orElse(""), id, url))
                .filter(candidate -> text(candidate, "url").isPresent())
                .findFirst();

            if (mirror.isPresent()) {
                final var mirrorUrl = text(mirror.get(), "url").map(value).orElseThrow();
                remotes.putIfAbsent(text(mirror.get(), "id").map(value).orElse(mirrorUrl), mirrorUrl);
            } else {
                remotes.putIfAbsent(id, url);
            }
        });

        final var servers = children(root, "servers", "server");

        return remotes.entrySet().stream()
            .map(remote -> new MavenRemote(
                remote.getKey(),
                URI.create(remote.getValue()),
                servers.stream()
                    .filter(server -> text(server, "id").map(value).orElse("").equals(remote.getKey()))
                    .findFirst()
                    .map(server -> headers(server, value))
                    .orElseGet(Map::of)
            ))
            .toList();
    }

    /**
     * Whether a {@code mirrorOf} mirrors the repository: {@code *}, an id,
     * {@code external:*} for any repository not on this machine, a list of
     * those separated by commas, and {@code !id} to leave one out.
     */
    static boolean mirrors(
        final String mirrorOf,
        final String id,
        final String url
    ) {
        var matches = false;

        for (final var pattern : mirrorOf.split(",")) {
            final var trimmed = pattern.trim();

            if (trimmed.startsWith("!") && trimmed.substring(1).equals(id)) {
                return false;
            }

            if (trimmed.equals("*") || trimmed.equals(id)
                || trimmed.equals("external:*") && isExternal(url)
                || trimmed.equals("external:http:*") && isExternal(url) && url.startsWith("http:")) {
                matches = true;
            }
        }

        return matches;
    }

    private static boolean isExternal(final String url) {
        try {
            final var uri = URI.create(url);
            final var host = uri.getHost() == null ? "" : uri.getHost();

            return !"file".equals(uri.getScheme()) && !host.equals("localhost") && !host.equals("127.0.0.1");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The ids of the active profiles: those {@code activeProfiles} lists
     * and those active by default.
     */
    private static Set<String> activeProfiles(
        final Element root,
        final Function<String, String> value
    ) {
        final var active = children(root, "activeProfiles", "activeProfile").stream()
            .map(profile -> value.apply(profile.getTextContent().trim()))
            .collect(Collectors.toSet());

        children(root, "profiles", "profile").stream()
            .filter(profile -> child(profile, "activation")
                .flatMap(activation -> text(activation, "activeByDefault"))
                .map(value)
                .orElse("false")
                .equals("true"))
            .forEach(profile -> text(profile, "id").map(value).ifPresent(active::add));

        return active;
    }

    /**
     * The HTTP headers with the credentials of a server.
     */
    private static Map<String, String> headers(
        final Element server,
        final Function<String, String> value
    ) {
        final var headers = new LinkedHashMap<String, String>();

        final var username = text(server, "username").map(value);
        final var password = text(server, "password").map(value);

        if (username.isPresent()) {
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (username.get() + ":" + password.orElse("")).getBytes(StandardCharsets.UTF_8)
            ));
        }

        child(server, "configuration")
            .map(configuration -> children(configuration, "httpHeaders", "property"))
            .orElseGet(List::of)
            .forEach(property -> text(property, "name").map(value).ifPresent(
                name -> headers.put(name, text(property, "value").map(value).orElse(""))
            ));

        return headers;
    }

    /**
     * Replaces {@code ${env.NAME}} with the value of the environment
     * variable; one that is not set is kept as it is, as Maven does.
     */
    static String interpolate(
        final String text,
        final Function<String, String> environment
    ) {
        return ENVIRONMENT_VARIABLE.matcher(text).replaceAll(reference -> Matcher.quoteReplacement(
            Optional.ofNullable(environment.apply(reference.group(1))).orElse(reference.group())
        ));
    }

    /**
     * The elements {@code name} in the element {@code list} of the parent,
     * e.g. the {@code server} elements of {@code servers}.
     */
    private static List<Element> children(
        final Element parent,
        final String list,
        final String name
    ) {
        return child(parent, list)
            .map(element -> elements(element, name))
            .orElseGet(List::of);
    }

    private static Optional<Element> child(
        final Element parent,
        final String name
    ) {
        return elements(parent, name).stream().findFirst();
    }

    private static List<Element> elements(
        final Element parent,
        final String name
    ) {
        final var elements = new ArrayList<Element>();
        final var nodes = parent.getChildNodes();

        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && element.getTagName().equals(name)) {
                elements.add(element);
            }
        }

        return elements;
    }

    private static Optional<String> text(
        final Element parent,
        final String name
    ) {
        return child(parent, name)
            .map(element -> element.getTextContent().trim())
            .filter(text -> !text.isEmpty());
    }
}
