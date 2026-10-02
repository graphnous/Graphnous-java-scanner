package dev.graphnous.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import dev.graphnous.scanner.model.Annotation;
import dev.graphnous.scanner.model.Class;
import dev.graphnous.scanner.model.Field;
import dev.graphnous.scanner.model.File;
import dev.graphnous.scanner.model.Method;
import dev.graphnous.scanner.model.Modifier;
import dev.graphnous.scanner.model.Parameter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JavaClassParser {

    /**
     * A parser per thread, as files are parsed in parallel and a
     * {@link JavaParser} is not thread-safe.
     */
    private final ThreadLocal<JavaParser> parser;

    public JavaClassParser() {
        this(ParserConfiguration.LanguageLevel.CURRENT);
    }

    /**
     * @param languageLevel the Java version the sources are written for
     */
    public JavaClassParser(final ParserConfiguration.LanguageLevel languageLevel) {
        this.parser = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration().setLanguageLevel(languageLevel)
        ));
    }

    /**
     * Collects the types the source files declare, to resolve the types
     * each of them uses. The files are parsed in parallel. Files that
     * cannot be parsed are left out; they are reported when they are
     * parsed with {@link #parse}.
     */
    public JavaTypeIndex index(final Collection<Path> sourcePaths) {
        final var files = sourcePaths.parallelStream()
            .map(this::indexFile)
            .flatMap(Optional::stream)
            .toList();

        return new JavaTypeIndex(
            files.stream().flatMap(file -> file.types().stream()).toList(),
            files.stream().map(IndexedFile::packageName).filter(name -> !name.isEmpty()).toList()
        );
    }

    private Optional<IndexedFile> indexFile(final Path sourcePath) {
        try {
            final var compilationUnit = parseSource(sourcePath);
            final var resolver = new JavaTypeResolver(compilationUnit);

            return Optional.of(new IndexedFile(
                packageName(compilationUnit),
                compilationUnit
                    .findAll(TypeDeclaration.class, JavaClassParser::isNamed)
                    .stream()
                    .map(declaration -> resolver.qualifiedName((TypeDeclaration<?>) declaration))
                    .toList()
            ));
        } catch (RuntimeException | StackOverflowError e) {
            // A file that cannot be analysed, e.g. one nested too deeply,
            // is skipped rather than failing the scan
            return Optional.empty();
        }
    }

    private record IndexedFile(
        String packageName,
        List<String> types
    ) {
    }

    private static String packageName(final CompilationUnit compilationUnit) {
        return compilationUnit.getPackageDeclaration()
            .map(declaration -> declaration.getNameAsString())
            .orElse("");
    }

    /**
     * Parses the source file on its own, without the other types of the
     * scan target; see {@link #parse(Path, File, JavaTypeIndex)}.
     */
    public String parse(
        final Path sourcePath,
        final File file
    ) {
        return parse(sourcePath, file, JavaTypeIndex.EMPTY);
    }

    /**
     * Parses the source file and sets its classes, with their methods,
     * fields and annotations, on {@code file}.
     *
     * @param index the types of the scan target, to resolve the types the
     *              file uses
     * @return the package of the file, empty for the default package
     * @throws JavaParseException when the source contains syntax errors
     */
    public String parse(
        final Path sourcePath,
        final File file,
        final JavaTypeIndex index
    ) {
        final CompilationUnit compilationUnit = parseSource(sourcePath);

        final var resolver = new JavaTypeResolver(compilationUnit, index);
        final var annotations = new JavaAnnotationParser(resolver);
        final var classes = new ArrayList<Class>();

        compilationUnit
            .findAll(TypeDeclaration.class, JavaClassParser::isNamed)
            .forEach(declaration -> classes.add(
                toClass(resolver, annotations, declaration)
            ));

        file.setClasses(classes);

        return packageName(compilationUnit);
    }

    /**
     * Whether the type has a qualified name: a top-level type or a member
     * of one. Types declared in a method, an anonymous class or the body
     * of an enum constant have none and cannot be used outside it, so
     * they are left out.
     */
    private static boolean isNamed(final TypeDeclaration<?> declaration) {
        Node current = declaration;

        while (current.getParentNode().orElse(null) instanceof TypeDeclaration<?> parent) {
            current = parent;
        }

        return current.getParentNode().orElse(null) instanceof CompilationUnit;
    }

    private CompilationUnit parseSource(final Path sourcePath) {
        try {
            final var result = parser.get().parse(sourcePath);

            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                throw new JavaParseException(sourcePath, result.getProblems());
            }

            return result.getResult().get();

        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to read Java file: " + sourcePath,
                e
            );
        }
    }

    private Class toClass(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final TypeDeclaration<?> declaration
    ) {
        final var clazz = new Class();

        clazz.setName(
            declaration.getNameAsString()
        );

        clazz.setQualifiedName(
            resolver.qualifiedName(declaration)
        );

        clazz.setKind(
            kind(declaration)
        );

        clazz.setModifiers(
            modifiers(declaration.getModifiers())
        );

        if (declaration instanceof NodeWithTypeParameters<?> generic) {
            clazz.setTypeParameters(
                typeParameters(resolver, generic.getTypeParameters())
            );
        }

        supertypes(resolver, clazz, declaration);

        clazz.setMethods(
            methods(resolver, annotations, clazz, declaration)
        );

        clazz.setFields(
            fields(resolver, annotations, clazz, declaration)
        );

        clazz.setAnnotations(
            annotations.annotations(declaration.getAnnotations())
        );

        return clazz;
    }

    private static Class.Kind kind(final TypeDeclaration<?> declaration) {
        return switch (declaration) {
            case ClassOrInterfaceDeclaration type when type.isInterface() -> Class.Kind.INTERFACE;
            case RecordDeclaration ignored -> Class.Kind.RECORD;
            case EnumDeclaration ignored -> Class.Kind.ENUM;
            case AnnotationDeclaration ignored -> Class.Kind.ANNOTATION;
            default -> Class.Kind.CLASS;
        };
    }

    /**
     * Sets the class a class extends and the interfaces a type implements,
     * or for an interface the interfaces it extends. The implicit
     * supertypes, such as {@code java.lang.Object} and
     * {@code java.lang.Record}, are left out.
     */
    private static void supertypes(
        final JavaTypeResolver resolver,
        final Class clazz,
        final TypeDeclaration<?> declaration
    ) {
        switch (declaration) {
            case ClassOrInterfaceDeclaration type when type.isInterface() ->
                clazz.setInterfaces(types(resolver, type.getExtendedTypes()));
            case ClassOrInterfaceDeclaration type -> {
                type.getExtendedTypes()
                    .getFirst()
                    .ifPresent(superClass -> clazz.setSuperClass(resolver.resolve(superClass, superClass)));

                clazz.setInterfaces(types(resolver, type.getImplementedTypes()));
            }
            case NodeWithImplements<?> type -> clazz.setInterfaces(types(resolver, type.getImplementedTypes()));
            default -> {
            }
        }
    }

    private static List<String> types(
        final JavaTypeResolver resolver,
        final NodeList<ClassOrInterfaceType> types
    ) {
        return types.stream()
            .map(type -> resolver.resolve(type, type))
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The type parameters with their qualified bounds, e.g.
     * {@code T extends java.lang.Comparable<T> & java.io.Serializable}.
     */
    private static List<String> typeParameters(
        final JavaTypeResolver resolver,
        final NodeList<TypeParameter> typeParameters
    ) {
        return typeParameters.stream()
            .map(typeParameter -> typeParameter.getNameAsString()
                + typeParameter.getTypeBound()
                    .stream()
                    .map(bound -> resolver.resolve(bound, typeParameter))
                    .collect(Collectors.joining(" & ", typeParameter.getTypeBound().isEmpty() ? "" : " extends ", "")))
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The modifiers written in the source; implicit ones, such as those of
     * the methods of an interface, are not included.
     */
    private static List<Modifier> modifiers(final NodeList<com.github.javaparser.ast.Modifier> modifiers) {
        return modifiers.stream()
            .map(modifier -> modifier.getKeyword().name())
            // TRANSITIVE only applies to module requirements
            .filter(keyword -> Arrays.stream(Modifier.values()).anyMatch(value -> value.name().equals(keyword)))
            .map(Modifier::valueOf)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The methods declared by the type itself, including its constructors
     * and the elements of an annotation. Methods of nested types belong to
     * those types.
     * <p>
     * Constructors are named after the type and have no return type, e.g.
     * {@code com.example.Order.Order(java.lang.String)}. Only declared
     * constructors are included, not the default constructor or the
     * canonical constructor of a record that the compiler adds.
     */
    private static List<Method> methods(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final Class clazz,
        final TypeDeclaration<?> declaration
    ) {
        final var methods = new ArrayList<Method>();

        declaration.getMembers().forEach(member -> {
            if (member instanceof CallableDeclaration<?> callable) {
                // Methods and constructors
                methods.add(method(
                    resolver,
                    annotations,
                    clazz,
                    callable,
                    callable.getModifiers(),
                    callable.getTypeParameters(),
                    callable.getParameters()
                ));
            } else if (member instanceof CompactConstructorDeclaration constructor
                && declaration instanceof RecordDeclaration record) {
                // The compact form of the canonical constructor takes the
                // record components
                methods.add(method(
                    resolver,
                    annotations,
                    clazz,
                    constructor,
                    constructor.getModifiers(),
                    constructor.getTypeParameters(),
                    record.getParameters()
                ));
            } else if (member instanceof AnnotationMemberDeclaration element) {
                methods.add(method(
                    resolver,
                    annotations,
                    clazz,
                    element,
                    element.getModifiers(),
                    new NodeList<>(),
                    new NodeList<>()
                ));
            }
        });

        return methods;
    }

    /**
     * The qualified name includes the qualified parameter types, so that
     * overloads of a method each get their own name, e.g.
     * {@code com.example.Orders.find(java.lang.String, java.util.List<com.example.Order>)}.
     */
    private static Method method(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final Class clazz,
        final BodyDeclaration<?> declaration,
        final NodeList<com.github.javaparser.ast.Modifier> modifiers,
        final NodeList<TypeParameter> typeParameters,
        final NodeList<com.github.javaparser.ast.body.Parameter> parameterDeclarations
    ) {
        final var method = new Method();
        final var name = ((NodeWithSimpleName<?>) declaration).getNameAsString();

        final var parameters = parameterDeclarations.stream()
            .map(parameter -> parameter(resolver, annotations, parameter))
            .toList();

        method.setName(name);

        method.setQualifiedName(
            clazz.getQualifiedName() + "." + name
            + parameters.stream()
                .map(Parameter::getType)
                .collect(Collectors.joining(", ", "(", ")"))
        );

        final var isConstructor = declaration instanceof ConstructorDeclaration
            || declaration instanceof CompactConstructorDeclaration;

        method.setKind(isConstructor ? Method.Kind.CONSTRUCTOR : Method.Kind.METHOD);
        method.setModifiers(modifiers(modifiers));
        method.setTypeParameters(typeParameters(resolver, typeParameters));

        switch (declaration) {
            case MethodDeclaration methodDeclaration ->
                method.setReturnType(resolver.resolve(methodDeclaration.getType(), methodDeclaration));
            case AnnotationMemberDeclaration element ->
                method.setReturnType(resolver.resolve(element.getType(), element));
            default -> {
            }
        }

        parameters.forEach(parameter ->
            parameter.setQualifiedName(method.getQualifiedName() + "." + parameter.getName())
        );

        method.setParameters(new ArrayList<>(parameters));
        method.setAnnotations(new ArrayList<>(annotations.annotations(declaration.getAnnotations())));

        return method;
    }

    /**
     * The type is the qualified type, e.g.
     * {@code java.util.List<java.lang.String>}; the qualified name is set
     * by {@link #method}, once the name of the method is known.
     */
    private static Parameter parameter(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final com.github.javaparser.ast.body.Parameter declaration
    ) {
        final var parameter = new Parameter();

        parameter.setName(
            declaration.getNameAsString()
        );

        parameter.setType(
            resolver.resolve(declaration.getType(), declaration)
            + (declaration.isVarArgs() ? "..." : "")
        );

        parameter.setAnnotations(
            new ArrayList<>(annotations.annotations(declaration.getAnnotations()))
        );

        return parameter;
    }

    /**
     * The fields declared by the type itself: one per variable of a field
     * declaration, the components of a record and the constants of an
     * enum. Fields of nested types belong to those types.
     */
    private static List<Field> fields(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final Class clazz,
        final TypeDeclaration<?> declaration
    ) {
        final var fields = new ArrayList<Field>();

        if (declaration instanceof RecordDeclaration record) {
            record.getParameters().forEach(component -> fields.add(field(
                clazz,
                component.getNameAsString(),
                resolver.resolve(component.getType(), component),
                modifiers(component.getModifiers()),
                annotations.annotations(component.getAnnotations())
            )));
        }

        if (declaration instanceof EnumDeclaration enumeration) {
            enumeration.getEntries().forEach(constant -> fields.add(field(
                clazz,
                constant.getNameAsString(),
                clazz.getQualifiedName(),
                new ArrayList<>(),
                annotations.annotations(constant.getAnnotations())
            )));
        }

        declaration.getMembers().forEach(member -> {
            if (member instanceof FieldDeclaration field) {
                // The type of the variable includes brackets written after
                // its name, as in int values[]
                field.getVariables().forEach(variable -> fields.add(field(
                    clazz,
                    variable.getNameAsString(),
                    resolver.resolve(variable.getType(), variable),
                    modifiers(field.getModifiers()),
                    annotations.annotations(field.getAnnotations())
                )));
            }
        });

        return fields;
    }

    private static Field field(
        final Class clazz,
        final String name,
        final String type,
        final List<Modifier> modifiers,
        final List<Annotation> annotations
    ) {
        final var field = new Field();

        field.setName(name);
        field.setQualifiedName(clazz.getQualifiedName() + "." + name);
        field.setType(type);
        field.setModifiers(modifiers);
        field.setAnnotations(new ArrayList<>(annotations));

        return field;
    }
}
