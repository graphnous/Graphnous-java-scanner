package dev.graphnous.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The local and anonymous classes of a source file, with the binary names
 * javac gives them, e.g. {@code com.example.Outer$1} for the first
 * anonymous class of {@code Outer} and {@code com.example.Outer$1Local}
 * for the first local class named {@code Local}.
 * <p>
 * As javac does, the classes are numbered per class that encloses them, in
 * the order of the source, the anonymous classes with one counter and the
 * local classes with a counter per name. The arguments of the creation of
 * an anonymous class come before it, as javac attributes them first.
 * <p>
 * An anonymous class is an {@link ObjectCreationExpr} with a body, or an
 * {@link EnumConstantDeclaration} with a body; a local class is a
 * {@link TypeDeclaration} declared in a block.
 */
final class JavaLocalClasses {

    private static final Comparator<Node> SOURCE_ORDER = Comparator.comparing(
        node -> node.getBegin().orElse(null),
        Comparator.nullsLast(Comparator.naturalOrder())
    );

    private final Map<Node, String> names = new IdentityHashMap<>();

    /**
     * The local and anonymous classes by the class that encloses them,
     * in source order.
     */
    private final Map<Node, List<Node>> classes = new IdentityHashMap<>();

    JavaLocalClasses(
        final CompilationUnit compilationUnit,
        final String packageName
    ) {
        compilationUnit.getTypes().forEach(type -> enter(
            type,
            packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString()
        ));
    }

    /**
     * The binary name of a local or anonymous class.
     */
    Optional<String> name(final Node declaration) {
        return Optional.ofNullable(names.get(declaration));
    }

    /**
     * The local and anonymous classes the class declares in its methods,
     * initializers and fields, in source order; not those of its member
     * classes or of the local and anonymous classes themselves.
     */
    List<Node> classesOf(final Node declaration) {
        return classes.getOrDefault(declaration, List.of());
    }

    static boolean isAnonymous(final Node node) {
        return node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()
            || node instanceof EnumConstantDeclaration constant && constant.getClassBody().isNonEmpty();
    }

    /**
     * The members of a class: of a type declaration, of the body of an
     * anonymous class, or of the body of an enum constant.
     */
    static NodeList<BodyDeclaration<?>> members(final Node declaration) {
        return switch (declaration) {
            case TypeDeclaration<?> type -> type.getMembers();
            case ObjectCreationExpr creation -> creation.getAnonymousClassBody().orElseGet(NodeList::new);
            case EnumConstantDeclaration constant -> constant.getClassBody();
            default -> new NodeList<>();
        };
    }

    /**
     * The method or constructor whose body declares the node, when it is
     * not in a field or initializer of its class.
     */
    static Optional<Node> enclosingCallable(final Node node) {
        Node child = node;

        for (Node current = node.getParentNode().orElse(null); current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof CallableDeclaration<?> || current instanceof CompactConstructorDeclaration) {
                return Optional.of(current);
            }

            // The class the node is in ends the search
            if (current instanceof TypeDeclaration<?> || isAnonymous(current) && isMember(child, current)) {
                return Optional.empty();
            }

            child = current;
        }

        return Optional.empty();
    }

    private static boolean isMember(
        final Node node,
        final Node declaration
    ) {
        for (final var member : members(declaration)) {
            if (member == node) {
                return true;
            }
        }

        return false;
    }

    private void enter(
        final Node declaration,
        final String binaryName
    ) {
        final var counters = new HashMap<String, Integer>();
        final var members = new ArrayList<Node>();

        // Enum constants come before the other members
        if (declaration instanceof EnumDeclaration enumeration) {
            members.addAll(enumeration.getEntries());
        }

        members.addAll(members(declaration));

        members.forEach(member -> visit(member, declaration, binaryName, counters));
    }

    private void visit(
        final Node node,
        final Node owner,
        final String ownerName,
        final Map<String, Integer> counters
    ) {
        switch (node) {
            case TypeDeclaration<?> type when type.getParentNode().orElse(null) == owner ->
                enter(type, ownerName + "$" + type.getNameAsString());
            case TypeDeclaration<?> type ->
                local(type, owner, ownerName + "$" + next(counters, type.getNameAsString()) + type.getNameAsString());
            case ObjectCreationExpr creation when creation.getAnonymousClassBody().isPresent() -> {
                creation.getScope().ifPresent(scope -> visit(scope, owner, ownerName, counters));
                creation.getArguments().forEach(argument -> visit(argument, owner, ownerName, counters));
                local(creation, owner, ownerName + "$" + next(counters, ""));
            }
            case EnumConstantDeclaration constant when constant.getClassBody().isNonEmpty() -> {
                constant.getArguments().forEach(argument -> visit(argument, owner, ownerName, counters));
                local(constant, owner, ownerName + "$" + next(counters, ""));
            }
            default -> node.getChildNodes()
                .stream()
                .sorted(SOURCE_ORDER)
                .forEach(child -> visit(child, owner, ownerName, counters));
        }
    }

    private void local(
        final Node declaration,
        final Node owner,
        final String binaryName
    ) {
        names.put(declaration, binaryName);
        classes.computeIfAbsent(owner, key -> new ArrayList<>()).add(declaration);

        enter(declaration, binaryName);
    }

    private static int next(
        final Map<String, Integer> counters,
        final String simpleName
    ) {
        return counters.merge(simpleName, 1, Integer::sum);
    }
}
