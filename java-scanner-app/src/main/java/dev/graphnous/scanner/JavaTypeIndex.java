package dev.graphnous.scanner;

import java.util.Collection;
import java.util.Set;

/**
 * The types declared by the sources of a scan target and the packages
 * they are in, so a type used in one file can be resolved to a type
 * declared in another.
 */
public final class JavaTypeIndex {

    static final JavaTypeIndex EMPTY = new JavaTypeIndex(Set.of(), Set.of());

    private final Set<String> types;

    private final Set<String> packages;

    /**
     * @param types    qualified names of the types, including nested
     *                 types, e.g. {@code com.example.Order.Line}
     * @param packages names of the packages the types are in
     */
    JavaTypeIndex(
        final Collection<String> types,
        final Collection<String> packages
    ) {
        this.types = Set.copyOf(types);
        this.packages = Set.copyOf(packages);
    }

    boolean containsType(final String qualifiedName) {
        return types.contains(qualifiedName);
    }

    boolean containsPackage(final String packageName) {
        return packages.contains(packageName);
    }

    int size() {
        return types.size();
    }
}
