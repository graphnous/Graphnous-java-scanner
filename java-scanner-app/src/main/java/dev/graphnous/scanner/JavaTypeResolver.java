package dev.graphnous.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.ast.type.WildcardType;
import dev.graphnous.core.model.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the types used in one source file to their fully qualified
 * names, including their type arguments, e.g. {@code Map<String, Order>}
 * → {@code java.util.Map<java.lang.String,com.example.Order>}. Type
 * variables have no qualified name and keep their name, e.g. {@code T}.
 * <p>
 * The classpath of the scanned project is not available, so types are
 * resolved from the sources of the scan target (see {@link JavaTypeIndex})
 * and the JDK, following the order of the Java language:
 * <ol>
 *     <li>type parameters,</li>
 *     <li>types declared in the file,</li>
 *     <li>single-type imports,</li>
 *     <li>scanned types in the package of the file,</li>
 *     <li>{@code java.lang},</li>
 *     <li>scanned and JDK types of on-demand imports.</li>
 * </ol>
 * A type found in none of these comes from a library. When the file has
 * exactly one on-demand import of a library package, e.g.
 * {@code import org.springframework.web.bind.annotation.*}, the type is
 * taken to be in that package; otherwise in the package of the file.
 */
class JavaTypeResolver {

    /**
     * The packages of the JDK the scanner runs on, to tell imports of the
     * JDK apart from imports of libraries.
     */
    private static final Set<String> JDK_PACKAGES = ModuleLayer.boot()
        .modules()
        .stream()
        .flatMap(module -> module.getPackages().stream())
        .collect(Collectors.toUnmodifiableSet());

    private final CompilationUnit compilationUnit;

    private final JavaTypeIndex index;

    private final String packageName;

    JavaTypeResolver(final CompilationUnit compilationUnit) {
        this(compilationUnit, JavaTypeIndex.EMPTY);
    }

    JavaTypeResolver(
        final CompilationUnit compilationUnit,
        final JavaTypeIndex index
    ) {
        this.compilationUnit = compilationUnit;
        this.index = index;
        this.packageName = compilationUnit
            .getPackageDeclaration()
            .map(NodeWithName::getNameAsString)
            .orElse("");
    }

    /**
     * @param context the node the type is used in, which decides the type
     *                parameters and nested types in scope
     */
    String resolve(
        final Type type,
        final Node context
    ) {
        return switch (type) {
            case PrimitiveType primitive -> primitive.asString();
            case VoidType ignored -> "void";
            case ArrayType array -> resolve(array.getComponentType(), context) + "[]";
            case ClassOrInterfaceType classType -> resolveName(classType.getNameWithScope(), context)
                + typeArguments(classType, context);
            case WildcardType wildcard -> wildcard(wildcard, context);
            default -> type.asString();
        };
    }

    /**
     * The resolved type with the qualified names of the classes it refers
     * to, including through type arguments, bounds and array element
     * types. Primitive types and type variables are left out.
     */
    TypeRef typeRef(
        final Type type,
        final Node context
    ) {
        return typeRef(type, context, "");
    }

    /**
     * @param suffix appended to the name, e.g. {@code ...} for varargs
     */
    TypeRef typeRef(
        final Type type,
        final Node context,
        final String suffix
    ) {
        final var typeRef = new TypeRef();
        final var references = new LinkedHashSet<String>();

        typeRef.setName(resolve(type, context) + suffix);
        references(type, context, references);
        typeRef.setReferences(references);

        return typeRef;
    }

    private void references(
        final Type type,
        final Node context,
        final Set<String> references
    ) {
        switch (type) {
            case ArrayType array -> references(array.getComponentType(), context, references);
            case ClassOrInterfaceType classType -> {
                final var name = classType.getNameWithScope();

                if (name.contains(".") || typeParameter(name, context).isEmpty()) {
                    references.add(resolveName(name, context));
                }

                classType.getTypeArguments()
                    .ifPresent(arguments -> arguments.forEach(argument -> references(argument, context, references)));
            }
            case WildcardType wildcard -> {
                wildcard.getExtendedType().ifPresent(bound -> references(bound, context, references));
                wildcard.getSuperType().ifPresent(bound -> references(bound, context, references));
            }
            default -> {
            }
        }
    }

    /**
     * The type arguments written in the same format as JavaParser writes
     * types, without spaces, e.g. {@code <java.lang.String,java.lang.Integer>},
     * and {@code <>} for the diamond.
     */
    private String typeArguments(
        final ClassOrInterfaceType type,
        final Node context
    ) {
        return type.getTypeArguments()
            .map(arguments -> arguments.stream()
                .map(argument -> resolve(argument, context))
                .collect(Collectors.joining(",", "<", ">")))
            .orElse("");
    }

    private String wildcard(
        final WildcardType wildcard,
        final Node context
    ) {
        return wildcard.getExtendedType()
            .map(bound -> "? extends " + resolve(bound, context))
            .or(() -> wildcard.getSuperType()
                .map(bound -> "? super " + resolve(bound, context)))
            .orElse("?");
    }

    /**
     * The qualified name of a type declared in this file, including the
     * types it is nested in, e.g. {@code com.example.Outer.Inner}.
     */
    String qualifiedName(final TypeDeclaration<?> declaration) {
        final var names = new ArrayList<String>();

        for (TypeDeclaration<?> current = declaration; current != null; current = enclosingType(current).orElse(null)) {
            names.addFirst(current.getNameAsString());
        }

        return qualify(packageName, String.join(".", names));
    }

    /**
     * Resolves a type name as written in the source, which may be simple,
     * nested or qualified, e.g. {@code Order}, {@code Map.Entry} or
     * {@code java.util.List}.
     */
    String resolveName(
        final String name,
        final Node context
    ) {
        final var dot = name.indexOf('.');

        if (dot < 0) {
            return resolveSimpleName(name, context);
        }

        final var first = name.substring(0, dot);

        // java.util.List is already qualified; Map.Entry starts with a type
        return Character.isLowerCase(first.charAt(0))
            ? name
            : resolveSimpleName(first, context) + name.substring(dot);
    }

    private String resolveSimpleName(
        final String name,
        final Node context
    ) {
        return typeParameter(name, context)
            .map(TypeParameter::getNameAsString)
            .or(() -> declaredType(name, context).map(this::qualifiedName))
            .or(() -> singleTypeImport(name))
            .or(() -> scannedType(packageName, name))
            .or(() -> jdkType("java.lang", name))
            .or(() -> onDemandImport(name))
            .or(() -> onlyLibraryImport(name))
            .orElseGet(() -> qualify(packageName, name));
    }

    private static Optional<TypeParameter> typeParameter(
        final String name,
        final Node context
    ) {
        for (Node current = context; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof NodeWithTypeParameters<?> generic) {
                final var match = generic.getTypeParameters()
                    .stream()
                    .filter(parameter -> parameter.getNameAsString().equals(name))
                    .findFirst();

                if (match.isPresent()) {
                    return match;
                }
            }
        }

        return Optional.empty();
    }

    /**
     * A type named {@code name} that is the enclosing type itself or one of
     * its member types, nearest first, or a top-level type of the file.
     */
    private Optional<TypeDeclaration<?>> declaredType(
        final String name,
        final Node context
    ) {
        for (Node current = context; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?> type) {
                if (type.getNameAsString().equals(name)) {
                    return Optional.of(type);
                }

                final var member = type.getMembers()
                    .stream()
                    .filter(TypeDeclaration.class::isInstance)
                    .<TypeDeclaration<?>>map(TypeDeclaration.class::cast)
                    .filter(nested -> nested.getNameAsString().equals(name))
                    .findFirst();

                if (member.isPresent()) {
                    return member;
                }
            }
        }

        return compilationUnit.getTypes()
            .stream()
            .filter(type -> type.getNameAsString().equals(name))
            .<TypeDeclaration<?>>map(type -> type)
            .findFirst();
    }

    private Optional<String> singleTypeImport(final String name) {
        return compilationUnit.getImports()
            .stream()
            .filter(declaration -> !declaration.isStatic() && !declaration.isAsterisk())
            .map(ImportDeclaration::getNameAsString)
            .filter(imported -> imported.equals(name) || imported.endsWith("." + name))
            .findFirst();
    }

    private Optional<String> onDemandImport(final String name) {
        return compilationUnit.getImports()
            .stream()
            .filter(declaration -> !declaration.isStatic() && declaration.isAsterisk())
            .map(ImportDeclaration::getNameAsString)
            .map(imported -> scannedType(imported, name).or(() -> jdkType(imported, name)))
            .flatMap(Optional::stream)
            .findFirst();
    }

    /**
     * The type in the package of the only on-demand import that is neither
     * a scanned package or type nor a JDK package.
     */
    private Optional<String> onlyLibraryImport(final String name) {
        final var libraries = compilationUnit.getImports()
            .stream()
            .filter(declaration -> !declaration.isStatic() && declaration.isAsterisk())
            .map(ImportDeclaration::getNameAsString)
            .filter(imported -> !index.containsPackage(imported)
                && !index.containsType(imported)
                && !JDK_PACKAGES.contains(imported))
            .toList();

        return libraries.size() == 1
            ? Optional.of(libraries.getFirst() + "." + name)
            : Optional.empty();
    }

    private Optional<String> scannedType(
        final String packageName,
        final String name
    ) {
        final var qualifiedName = qualify(packageName, name);

        return index.containsType(qualifiedName)
            ? Optional.of(qualifiedName)
            : Optional.empty();
    }

    /**
     * Whether the JDK the scanner runs on has the type, which is how
     * {@code java.lang} and on-demand imports of JDK packages resolve.
     */
    private static Optional<String> jdkType(
        final String packageName,
        final String name
    ) {
        final var qualifiedName = packageName + "." + name;

        try {
            java.lang.Class.forName(qualifiedName, false, ClassLoader.getPlatformClassLoader());

            return Optional.of(qualifiedName);
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static Optional<TypeDeclaration<?>> enclosingType(final TypeDeclaration<?> declaration) {
        return declaration
            .getParentNode()
            .filter(TypeDeclaration.class::isInstance)
            .map(parent -> (TypeDeclaration<?>) parent);
    }

    private static String qualify(
        final String packageName,
        final String name
    ) {
        return packageName.isBlank()
            ? name
            : packageName + "." + name;
    }
}
