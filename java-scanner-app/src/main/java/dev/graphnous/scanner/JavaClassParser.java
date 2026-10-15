package dev.graphnous.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import dev.graphnous.scanner.model.Annotation;
import dev.graphnous.scanner.model.Class;
import dev.graphnous.scanner.model.EnumConstant;
import dev.graphnous.scanner.model.Field;
import dev.graphnous.scanner.model.File;
import dev.graphnous.scanner.model.Import;
import dev.graphnous.scanner.model.Method;
import dev.graphnous.scanner.model.Modifier;
import dev.graphnous.scanner.model.Parameter;
import dev.graphnous.scanner.model.RecordComponent;
import dev.graphnous.scanner.model.TypeRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JavaClassParser {

    /**
     * A parser per thread, as files are parsed in parallel and a
     * {@link JavaParser} is not thread-safe.
     */
    private final ThreadLocal<JavaParser> parser;

    private final ParserConfiguration.LanguageLevel languageLevel;

    public JavaClassParser() {
        this(ParserConfiguration.LanguageLevel.CURRENT);
    }

    /**
     * @param languageLevel the Java version the sources are written for
     */
    public JavaClassParser(final ParserConfiguration.LanguageLevel languageLevel) {
        this.languageLevel = languageLevel;
        this.parser = ThreadLocal.withInitial(() -> new JavaParser(configuration()));
    }

    private ParserConfiguration configuration() {
        return new ParserConfiguration().setLanguageLevel(languageLevel);
    }

    /**
     * Collects the types the source files declare, to resolve the types
     * each of them uses. The files are parsed in parallel. Files that
     * cannot be parsed are left out; they are reported when they are
     * parsed with {@link #parse}.
     */
    public JavaTypeIndex index(final Collection<Path> sourcePaths) {
        return index(sourcePaths, List.of());
    }

    /**
     * @param libraries the jars of the libraries the sources use, to
     *                  resolve the methods and fields of their types
     */
    public JavaTypeIndex index(
        final Collection<Path> sourcePaths,
        final Collection<Path> libraries
    ) {
        final var files = sourcePaths.parallelStream()
            .map(this::indexFile)
            .flatMap(Optional::stream)
            .toList();

        return new JavaTypeIndex(
            files.stream().flatMap(file -> file.types().stream()).toList(),
            files.stream().map(IndexedFile::packageName).filter(name -> !name.isEmpty()).toList(),
            files.stream().map(IndexedFile::sourceRoot).flatMap(Optional::stream).toList(),
            libraries,
            languageLevel
        );
    }

    private Optional<IndexedFile> indexFile(final Path sourcePath) {
        try {
            final var compilationUnit = parseSource(sourcePath);
            final var resolver = new JavaTypeResolver(compilationUnit);

            return Optional.of(new IndexedFile(
                packageName(compilationUnit),
                sourceRoot(sourcePath, packageName(compilationUnit)),
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
        Optional<Path> sourceRoot,
        List<String> types
    ) {
    }

    /**
     * The directory the package of the file starts in, e.g.
     * {@code src/main/java} for {@code src/main/java/com/example/Order.java}
     * in package {@code com.example}; none when the directories of the file
     * do not match its package.
     */
    private static Optional<Path> sourceRoot(
        final Path sourcePath,
        final String packageName
    ) {
        Path root = sourcePath.toAbsolutePath().normalize().getParent();

        if (!packageName.isEmpty()) {
            final var names = packageName.split("\\.");

            for (int i = names.length - 1; i >= 0; i--) {
                if (root == null || root.getFileName() == null || !root.getFileName().toString().equals(names[i])) {
                    return Optional.empty();
                }

                root = root.getParent();
            }
        }

        return Optional.ofNullable(root);
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
     * Parses the source file and sets its package, its imports and its
     * top-level classes, with their methods, fields, annotations and member
     * classes, on {@code file}.
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

        // Resolves the methods and fields the method bodies use
        new JavaSymbolSolver(index.typeSolver()).inject(compilationUnit);

        final var resolver = new JavaTypeResolver(compilationUnit, index);
        final var annotations = new JavaAnnotationParser(resolver);
        final var bodies = new JavaBodyParser(resolver, index);
        final var packageName = packageName(compilationUnit);

        file.setImports(
            compilationUnit.getImports()
                .stream()
                // Module imports, as in import module java.base, name no
                // type or package
                .filter(declaration -> !declaration.isModule())
                .map(JavaClassParser::toImport)
                .collect(Collectors.toCollection(ArrayList::new))
        );

        file.setClasses(
            compilationUnit.getTypes()
                .stream()
                .map(declaration -> toClass(resolver, annotations, bodies, declaration))
                .collect(Collectors.toCollection(ArrayList::new))
        );

        // Absent for the default package
        file.setPackage(packageName.isEmpty() ? null : packageName);

        return packageName;
    }

    /**
     * Whether the type has a qualified name: a top-level type or a member
     * of one. Types declared in a method, an anonymous class or the body
     * of an enum constant have none and cannot be used outside it, so
     * they are left out.
     */
    static boolean isNamed(final TypeDeclaration<?> declaration) {
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

    private static Import toImport(final ImportDeclaration declaration) {
        final var anImport = new Import();

        anImport.setName(declaration.getNameAsString());
        anImport.setStatic(declaration.isStatic());
        anImport.setWildcard(declaration.isAsterisk());

        JavaLocations.locate(declaration, anImport);

        return anImport;
    }

    private Class toClass(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final JavaBodyParser bodies,
        final TypeDeclaration<?> declaration
    ) {
        final var clazz = new Class();

        clazz.setName(
            declaration.getNameAsString()
        );

        clazz.setQualifiedName(
            resolver.qualifiedName(declaration)
        );

        JavaLocations.locate(declaration, clazz);

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
            methods(resolver, annotations, bodies, clazz, declaration)
        );

        clazz.setFields(
            fields(resolver, annotations, clazz, declaration)
        );

        if (declaration instanceof EnumDeclaration enumeration) {
            clazz.setEnumConstants(
                enumConstants(annotations, clazz, enumeration)
            );
        }

        if (declaration instanceof RecordDeclaration record) {
            clazz.setRecordComponents(
                recordComponents(resolver, annotations, record)
            );
        }

        clazz.setAnnotations(
            annotations.annotations(declaration.getAnnotations())
        );

        clazz.setClasses(nestedClasses(resolver, annotations, bodies, declaration));

        return clazz;
    }

    /**
     * The member classes of the class, followed by the local and anonymous
     * classes declared in its methods, fields and initializers.
     */
    private List<Class> nestedClasses(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final JavaBodyParser bodies,
        final Node declaration
    ) {
        final var classes = new ArrayList<Class>();

        JavaLocalClasses.members(declaration).forEach(member -> {
            if (member instanceof TypeDeclaration<?> type) {
                final var nested = toClass(resolver, annotations, bodies, type);
                nested.setNesting(Class.Nesting.MEMBER);
                classes.add(nested);
            }
        });

        resolver.localClasses().classesOf(declaration).forEach(local -> {
            final var nested = local instanceof TypeDeclaration<?> type
                ? toClass(resolver, annotations, bodies, type)
                : toAnonymousClass(resolver, annotations, bodies, local);

            nested.setNesting(local instanceof TypeDeclaration<?> ? Class.Nesting.LOCAL : Class.Nesting.ANONYMOUS);

            JavaLocalClasses.enclosingCallable(local)
                .map(callable -> enclosingMethodName(resolver, callable))
                .ifPresent(nested::setEnclosingMethod);

            classes.add(nested);
        });

        return classes;
    }

    /**
     * An anonymous class, named after its binary name, e.g. {@code 1} for
     * {@code com.example.Outer$1}, with the class it extends or the
     * interface it implements.
     */
    private Class toAnonymousClass(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final JavaBodyParser bodies,
        final Node declaration
    ) {
        final var clazz = new Class();
        final var qualifiedName = resolver.anonymousName(declaration).orElseThrow();

        clazz.setName(qualifiedName.substring(qualifiedName.lastIndexOf('$') + 1));
        clazz.setQualifiedName(qualifiedName);
        clazz.setKind(Class.Kind.CLASS);

        JavaLocations.locate(declaration, clazz);

        switch (declaration) {
            case ObjectCreationExpr creation -> {
                final var supertype = resolver.typeRef(creation.getType(), creation);

                if (isInterface(creation.getType())) {
                    clazz.setInterfaces(new ArrayList<>(List.of(supertype)));
                } else {
                    clazz.setSuperClasses(new ArrayList<>(List.of(supertype)));
                }
            }
            case EnumConstantDeclaration constant -> constant.findAncestor(EnumDeclaration.class).ifPresent(enumeration -> {
                final var supertype = new TypeRef();
                supertype.setName(resolver.qualifiedName(enumeration));
                supertype.setReferences(new LinkedHashSet<>(List.of(supertype.getName())));
                clazz.setSuperClasses(new ArrayList<>(List.of(supertype)));
            });
            default -> {
            }
        }

        clazz.setMethods(methods(resolver, annotations, bodies, clazz, declaration));
        clazz.setFields(fields(resolver, annotations, clazz, declaration));
        clazz.setClasses(nestedClasses(resolver, annotations, bodies, declaration));

        return clazz;
    }

    /**
     * Whether the type is an interface, as far as the symbol solver can
     * tell; a type it cannot resolve is taken to be a class.
     */
    private static boolean isInterface(final ClassOrInterfaceType type) {
        try {
            return type.resolve().asReferenceType().getTypeDeclaration().map(declaration -> declaration.isInterface()).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The qualified name of a method or constructor, in whatever class it
     * is declared, see {@link #qualifiedName(JavaTypeResolver, String, String, NodeList)}.
     */
    static String enclosingMethodName(
        final JavaTypeResolver resolver,
        final Node callable
    ) {
        final var parent = callable.getParentNode().orElseThrow();
        final var typeName = parent instanceof TypeDeclaration<?> type
            ? resolver.qualifiedName(type)
            : resolver.anonymousName(parent).orElseThrow();

        return switch (callable) {
            case CompactConstructorDeclaration constructor -> qualifiedName(
                resolver,
                typeName,
                constructor.getNameAsString(),
                ((RecordDeclaration) parent).getParameters()
            );
            case CallableDeclaration<?> declaration -> qualifiedName(
                resolver,
                typeName,
                declaration.getNameAsString(),
                declaration.getParameters()
            );
            default -> throw new IllegalArgumentException("Not a method or constructor: " + callable);
        };
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
                // A Java class extends at most one class
                clazz.setSuperClasses(types(resolver, type.getExtendedTypes()));
                clazz.setInterfaces(types(resolver, type.getImplementedTypes()));
            }
            case NodeWithImplements<?> type -> clazz.setInterfaces(types(resolver, type.getImplementedTypes()));
            default -> {
            }
        }
    }

    private static List<TypeRef> types(
        final JavaTypeResolver resolver,
        final NodeList<ClassOrInterfaceType> types
    ) {
        return types.stream()
            .map(type -> resolver.typeRef(type, type))
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
     * canonical constructor of a record that the compiler adds, unless
     * initializers use anything; see {@link #initializers}.
     */
    private static List<Method> methods(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final JavaBodyParser bodies,
        final Class clazz,
        final Node declaration
    ) {
        final var methods = new ArrayList<Method>();

        // The constructors that run the instance initializers: those that do
        // not hand over to another one with this(...)
        final var initializing = new ArrayList<Method>();
        var hasConstructor = false;

        for (final var member : JavaLocalClasses.members(declaration)) {
            if (member instanceof CallableDeclaration<?> callable) {
                // Methods and constructors
                methods.add(method(
                    resolver,
                    annotations,
                    bodies,
                    clazz,
                    callable,
                    callable.getModifiers(),
                    callable.getTypeParameters(),
                    callable.getParameters(),
                    callable.getThrownExceptions()
                ));

                if (callable instanceof ConstructorDeclaration constructor) {
                    hasConstructor = true;

                    if (!delegates(constructor)) {
                        initializing.add(methods.getLast());
                    }
                }
            } else if (member instanceof CompactConstructorDeclaration constructor
                && declaration instanceof RecordDeclaration record) {
                // The compact form of the canonical constructor takes the
                // record components
                methods.add(method(
                    resolver,
                    annotations,
                    bodies,
                    clazz,
                    constructor,
                    constructor.getModifiers(),
                    constructor.getTypeParameters(),
                    record.getParameters(),
                    constructor.getThrownExceptions()
                ));
                hasConstructor = true;
                initializing.add(methods.getLast());
            } else if (member instanceof AnnotationMemberDeclaration element) {
                methods.add(method(
                    resolver,
                    annotations,
                    bodies,
                    clazz,
                    element,
                    element.getModifiers(),
                    new NodeList<>(),
                    new NodeList<>(),
                    new NodeList<>()
                ));
            }
        }

        initializers(bodies, clazz, declaration, methods, initializing, hasConstructor);

        return methods;
    }

    /**
     * Gives what initializers use to what runs them, before what its body
     * uses:
     * <ul>
     * <li>instance field initializers and initializer blocks to each
     * constructor that runs them, or the default constructor the compiler
     * adds, e.g. {@code com.example.Order.Order()}, listed when they use
     * anything;</li>
     * <li>static field initializers, static initializer blocks, the fields of
     * interfaces and the arguments of enum constants to a method
     * {@code static} with the {@code STATIC} modifier, e.g.
     * {@code com.example.Order.static()}, listed when they use
     * anything.</li>
     * </ul>
     * Those of anonymous classes belong to what declares the class, see
     * {@link JavaBodyParser}.
     */
    private static void initializers(
        final JavaBodyParser bodies,
        final Class clazz,
        final Node declaration,
        final List<Method> methods,
        final List<Method> constructors,
        final boolean hasConstructor
    ) {
        if (JavaLocalClasses.isAnonymous(declaration)) {
            return;
        }

        final var isInterface = declaration instanceof ClassOrInterfaceDeclaration type && type.isInterface()
            || declaration instanceof AnnotationDeclaration;

        final var instance = new ArrayList<Node>();
        final var statics = new ArrayList<Node>();

        for (final var member : JavaLocalClasses.members(declaration)) {
            if (member instanceof FieldDeclaration field) {
                final var into = isInterface || field.isStatic() ? statics : instance;

                field.getVariables().forEach(variable -> variable.getInitializer().ifPresent(into::add));
            } else if (member instanceof InitializerDeclaration initializer) {
                (initializer.isStatic() ? statics : instance).add(initializer.getBody());
            }
        }

        if (declaration instanceof EnumDeclaration enumeration) {
            enumeration.getEntries().forEach(entry -> statics.addAll(entry.getArguments()));
        }

        if (!instance.isEmpty()) {
            if (hasConstructor) {
                constructors.forEach(constructor -> use(bodies, constructor, instance));
            } else {
                final var name = ((NodeWithSimpleName<?>) declaration).getNameAsString();
                final var constructor = new Method();

                constructor.setName(name);
                constructor.setQualifiedName(clazz.getQualifiedName() + "." + name + "()");
                constructor.setKind(Method.Kind.CONSTRUCTOR);

                if (use(bodies, constructor, instance)) {
                    methods.addFirst(constructor);
                }
            }
        }

        if (!statics.isEmpty()) {
            final var initializer = new Method();

            initializer.setName("static");
            initializer.setQualifiedName(clazz.getQualifiedName() + ".static()");
            initializer.setKind(Method.Kind.METHOD);
            initializer.setModifiers(new ArrayList<>(List.of(Modifier.STATIC)));

            if (use(bodies, initializer, statics)) {
                methods.add(initializer);
            }
        }
    }

    /**
     * Adds what the nodes use to the method, before what it uses already.
     *
     * @return whether they use anything
     */
    private static boolean use(
        final JavaBodyParser bodies,
        final Method method,
        final List<Node> nodes
    ) {
        final var uses = new Method();

        nodes.forEach(node -> bodies.parse(node, uses));

        method.setCalls(concat(uses.getCalls(), method.getCalls()));
        method.setFieldAccesses(concat(uses.getFieldAccesses(), method.getFieldAccesses()));
        method.setTypeUses(concat(uses.getTypeUses(), method.getTypeUses()));

        return !uses.getCalls().isEmpty() || !uses.getFieldAccesses().isEmpty() || !uses.getTypeUses().isEmpty();
    }

    private static <T> List<T> concat(final List<T> first, final List<T> second) {
        final var result = new ArrayList<T>(first);

        if (second != null) {
            result.addAll(second);
        }

        return result;
    }

    /**
     * Whether the constructor hands over to another one of its class with
     * this(...), which runs the initializers instead.
     */
    private static boolean delegates(final ConstructorDeclaration constructor) {
        return constructor.getBody().getStatements().getFirst()
            .filter(statement -> statement instanceof ExplicitConstructorInvocationStmt invocation && invocation.isThis())
            .isPresent();
    }

    /**
     * The method with its signature, named as in {@link #qualifiedName},
     * and what its body uses; see {@link JavaBodyParser}.
     */
    private static Method method(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final JavaBodyParser bodies,
        final Class clazz,
        final BodyDeclaration<?> declaration,
        final NodeList<com.github.javaparser.ast.Modifier> modifiers,
        final NodeList<TypeParameter> typeParameters,
        final NodeList<com.github.javaparser.ast.body.Parameter> parameterDeclarations,
        final NodeList<ReferenceType> thrownExceptions
    ) {
        final var method = new Method();
        final var name = ((NodeWithSimpleName<?>) declaration).getNameAsString();

        final var parameters = parameterDeclarations.stream()
            .map(parameter -> parameter(resolver, annotations, parameter))
            .toList();

        method.setName(name);

        method.setQualifiedName(
            qualifiedName(resolver, clazz.getQualifiedName(), name, parameterDeclarations)
        );

        JavaLocations.locate(declaration, method);

        final var isConstructor = declaration instanceof ConstructorDeclaration
            || declaration instanceof CompactConstructorDeclaration;

        method.setKind(isConstructor ? Method.Kind.CONSTRUCTOR : Method.Kind.METHOD);
        method.setModifiers(modifiers(modifiers));
        method.setTypeParameters(typeParameters(resolver, typeParameters));

        method.setThrows(
            thrownExceptions.stream()
                .map(type -> resolver.typeRef(type, declaration))
                .collect(Collectors.toCollection(ArrayList::new))
        );

        switch (declaration) {
            case MethodDeclaration methodDeclaration ->
                method.setReturnType(resolver.typeRef(methodDeclaration.getType(), methodDeclaration));
            case AnnotationMemberDeclaration element -> {
                method.setReturnType(resolver.typeRef(element.getType(), element));
                element.getDefaultValue().map(annotations::value).ifPresent(method::setDefaultValue);
            }
            default -> {
            }
        }

        parameters.forEach(parameter ->
            parameter.setQualifiedName(method.getQualifiedName() + "." + parameter.getName())
        );

        method.setParameters(new ArrayList<>(parameters));
        method.setAnnotations(new ArrayList<>(annotations.annotations(declaration.getAnnotations())));

        // What the methods of an anonymous class declared in a method use
        // belongs to that method, see JavaBodyParser
        final var inMethod = declaration.getParentNode()
            .filter(JavaLocalClasses::isAnonymous)
            .flatMap(JavaLocalClasses::enclosingCallable)
            .isPresent();

        if (!inMethod) {
            switch (declaration) {
                case MethodDeclaration methodDeclaration -> methodDeclaration.getBody().ifPresent(body -> bodies.parse(body, method));
                case ConstructorDeclaration constructor -> bodies.parse(constructor.getBody(), method);
                case CompactConstructorDeclaration constructor -> bodies.parse(constructor.getBody(), method);
                default -> {
                }
            }
        }

        return method;
    }

    /**
     * The qualified name of a method or constructor of the type: the
     * qualified parameter types make each overload a name of its own, e.g.
     * {@code com.example.Orders.find(java.lang.String, java.util.List<com.example.Order>)}.
     */
    static String qualifiedName(
        final JavaTypeResolver resolver,
        final String typeName,
        final String name,
        final NodeList<com.github.javaparser.ast.body.Parameter> parameters
    ) {
        return typeName + "." + name
            + parameters.stream()
                .map(parameter -> resolver.resolve(parameter.getType(), parameter) + (parameter.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(", ", "(", ")"));
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

        JavaLocations.locate(declaration, parameter);

        parameter.setType(
            resolver.typeRef(declaration.getType(), declaration, declaration.isVarArgs() ? "..." : "")
        );

        parameter.setAnnotations(
            new ArrayList<>(annotations.annotations(declaration.getAnnotations()))
        );

        return parameter;
    }

    /**
     * The constants of an enum, in declaration order.
     */
    private static List<EnumConstant> enumConstants(
        final JavaAnnotationParser annotations,
        final Class clazz,
        final EnumDeclaration enumeration
    ) {
        return enumeration.getEntries()
            .stream()
            .map(declaration -> {
                final var constant = new EnumConstant();

                constant.setName(declaration.getNameAsString());
                JavaLocations.locate(declaration, constant);
                constant.setQualifiedName(clazz.getQualifiedName() + "." + declaration.getNameAsString());
                constant.setAnnotations(new ArrayList<>(annotations.annotations(declaration.getAnnotations())));

                return constant;
            })
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The components of a record, in declaration order.
     */
    private static List<RecordComponent> recordComponents(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final RecordDeclaration record
    ) {
        return record.getParameters()
            .stream()
            .map(declaration -> {
                final var component = new RecordComponent();

                component.setName(declaration.getNameAsString());
                JavaLocations.locate(declaration, component);
                component.setType(resolver.typeRef(declaration.getType(), declaration));
                component.setAnnotations(new ArrayList<>(annotations.annotations(declaration.getAnnotations())));

                return component;
            })
            .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The fields declared by the type itself: one per variable of a field
     * declaration. The components of a record and the constants of an enum
     * are in {@link #recordComponents} and {@link #enumConstants}, and
     * fields of nested types belong to those types.
     */
    private static List<Field> fields(
        final JavaTypeResolver resolver,
        final JavaAnnotationParser annotations,
        final Class clazz,
        final Node declaration
    ) {
        final var fields = new ArrayList<Field>();

        JavaLocalClasses.members(declaration).forEach(member -> {
            if (member instanceof FieldDeclaration field) {
                // The type of the variable includes brackets written after
                // its name, as in int values[]
                // Each variable is located at the whole declaration, which
                // holds the annotations and modifiers they share
                field.getVariables().forEach(variable -> {
                    final var element = field(
                        clazz,
                        variable.getNameAsString(),
                        resolver.typeRef(variable.getType(), variable),
                        modifiers(field.getModifiers()),
                        annotations.annotations(field.getAnnotations())
                    );

                    JavaLocations.locate(field, element);
                    fields.add(element);
                });
            }
        });

        return fields;
    }

    private static Field field(
        final Class clazz,
        final String name,
        final TypeRef type,
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
