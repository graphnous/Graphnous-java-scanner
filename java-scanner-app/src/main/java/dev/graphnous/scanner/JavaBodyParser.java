package dev.graphnous.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import dev.graphnous.scanner.model.Call;
import dev.graphnous.scanner.model.FieldAccess;
import dev.graphnous.scanner.model.Method;
import dev.graphnous.scanner.model.TypeUse;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Finds what the body of a method or constructor uses: the methods and
 * constructors it calls, the fields it reads and writes, and the other
 * types it refers to, one entry per use, in source order.
 * <p>
 * Calls and field accesses are resolved with the JavaParser symbol solver
 * against the JDK, the sources of the scan target and the jars of its
 * libraries when they are available (see {@link JavaTypeIndex#typeSolver});
 * their targets are named as their declarations are in the scan result,
 * e.g. {@code com.example.Orders.find(java.lang.String)}. Calls into
 * libraries whose jars are not available are not resolved: their target
 * is the name as written, e.g. {@code log.info}, and {@code resolved} is
 * false. Field accesses that cannot be resolved are left out, as a name
 * alone does not tell a field from a variable.
 * <p>
 * Uses inside lambdas, anonymous classes and local classes declared in
 * the body belong to the method, as local and anonymous classes are not
 * in the scan result themselves. Creating an anonymous class is a use of
 * the type it extends or implements, of kind {@code OTHER}.
 */
final class JavaBodyParser {

    private final JavaTypeResolver resolver;

    private final JavaTypeIndex index;

    /**
     * Resolvers for the other files that declare the called methods, to
     * name them as their own files do.
     */
    private final Map<CompilationUnit, JavaTypeResolver> resolvers = new IdentityHashMap<>();

    /**
     * The methods the calls resolve to, see {@link #method}.
     */
    private final Map<MethodCallExpr, Optional<ResolvedMethodDeclaration>> methods = new IdentityHashMap<>();

    /**
     * The overloads of a method of a type, by the qualified name of the
     * type and the name of the method, see {@link #overloads}.
     */
    private final Map<String, List<ResolvedMethodDeclaration>> overloads = new HashMap<>();

    JavaBodyParser(
        final JavaTypeResolver resolver,
        final JavaTypeIndex index
    ) {
        this.resolver = resolver;
        this.index = index;
    }

    void parse(
        final Node body,
        final Method method
    ) {
        body.walk(node -> {
            switch (node) {
                case MethodCallExpr call -> {
                    call(method, call, Call.Kind.METHOD, target(call).or(() -> byArity(call)), () -> writtenName(call));
                    call.getTypeArguments().ifPresent(arguments ->
                        arguments.forEach(argument -> typeUses(method, argument, TypeUse.Kind.TYPE_ARGUMENT)));
                }
                case ObjectCreationExpr creation when creation.getAnonymousClassBody().isPresent() ->
                    // An anonymous class has no constructor of its own to
                    // name, so it uses the type it extends or implements
                    typeUses(method, creation.getType(), TypeUse.Kind.OTHER);
                case ObjectCreationExpr creation -> {
                    call(
                        method,
                        creation,
                        Call.Kind.CONSTRUCTOR,
                        target(creation::resolve).or(() -> constructorByArity(declaration(creation.getType()), creation.getArguments().size())),
                        () -> constructorName(creation.getType())
                    );
                    creation.getType().getTypeArguments().ifPresent(arguments ->
                        arguments.forEach(argument -> typeUses(method, argument, TypeUse.Kind.TYPE_ARGUMENT)));
                }
                case ExplicitConstructorInvocationStmt invocation -> call(
                    method,
                    invocation,
                    Call.Kind.CONSTRUCTOR,
                    target(invocation::resolve).or(() -> constructorByArity(invoked(invocation), invocation.getArguments().size())),
                    () -> writtenName(invocation)
                );
                case MethodReferenceExpr reference ->
                    call(method, reference, Call.Kind.METHOD_REFERENCE, target(reference::resolve), () -> writtenName(reference));
                case FieldAccessExpr access -> fieldAccess(method, access, access::resolve);
                case NameExpr name -> fieldAccess(method, name, name::resolve);
                case VariableDeclarationExpr variables -> typeUses(method, variables.getElementType(), TypeUse.Kind.LOCAL_VARIABLE);
                case CastExpr cast -> typeUses(method, cast.getType(), TypeUse.Kind.CAST);
                case InstanceOfExpr instanceOf -> typeUses(method, instanceOf.getType(), TypeUse.Kind.INSTANCEOF);
                case ClassExpr classLiteral -> typeUses(method, classLiteral.getType(), TypeUse.Kind.CLASS_LITERAL);
                case CatchClause catchClause -> typeUses(method, catchClause.getParameter().getType(), TypeUse.Kind.CATCH);
                case ArrayCreationExpr array -> typeUses(method, array.getElementType(), TypeUse.Kind.OTHER);
                case LambdaExpr lambda -> lambda.getParameters()
                    .stream()
                    .filter(parameter -> !(parameter.getType() instanceof UnknownType))
                    .forEach(parameter -> typeUses(method, parameter.getType(), TypeUse.Kind.OTHER));
                default -> {
                }
            }
        });
    }

    /**
     * @param target      the qualified name of the method or constructor
     *                    called, when it is resolved
     * @param writtenName the target when it is not resolved
     */
    private void call(
        final Method method,
        final Node node,
        final Call.Kind kind,
        final Optional<String> target,
        final Supplier<String> writtenName
    ) {
        final var call = new Call();

        call.setKind(kind);

        target.ifPresentOrElse(
            call::setTarget,
            () -> {
                call.setTarget(writtenName.get());
                call.setResolved(false);
            }
        );

        JavaLocations.locate(node, call);
        method.getCalls().add(call);
    }

    private Optional<String> target(final Supplier<? extends ResolvedMethodLikeDeclaration> resolve) {
        return resolve(() -> qualifiedName(resolve.get()));
    }

    private Optional<String> target(final MethodCallExpr call) {
        return method(call).flatMap(declaration -> resolve(() -> qualifiedName(declaration)));
    }

    /**
     * The method the call resolves to, checked for a fault of the symbol
     * solver: it widens and boxes the elements of arrays, taking e.g. an
     * {@code int[]} for a {@code double[]} or a {@code boolean[]} for a
     * {@code Boolean[]}, so for overloads that differ in those, such as
     * {@code clone(int[])} and {@code clone(double[])}, it picks any of them
     * or none, differently from one run to the next. When the method it
     * picks does not take the arguments, or it cannot resolve the call,
     * the call resolves to the most specific overload that takes them, as
     * Java picks it.
     * <p>
     * Kept per call, as calls nested in the arguments of other calls are
     * resolved again for those.
     */
    private Optional<ResolvedMethodDeclaration> method(final MethodCallExpr call) {
        final var known = methods.get(call);

        if (known != null) {
            return known;
        }

        final var method = resolveMethod(call);
        methods.put(call, method);

        return method;
    }

    private Optional<ResolvedMethodDeclaration> resolveMethod(final MethodCallExpr call) {
        try {
            final var declaration = call.resolve();

            if (!isSuspect(declaration, call)) {
                return Optional.of(declaration);
            }

            final var arguments = argumentTypes(call);

            if (accepts(declaration, arguments, false) && !takesArrayAsObject(declaration, arguments)) {
                return Optional.of(declaration);
            }
        } catch (RuntimeException e) {
            // Possibly the fault, see below: an ambiguity, or a call whose
            // scope or arguments have a call the solver found ambiguous
        } catch (StackOverflowError e) {
            return Optional.empty();
        }

        final var arguments = argumentTypes(call);

        return resolve(() -> {
            final var applicable = overloads(call)
                .stream()
                .filter(candidate -> accepts(candidate, arguments, true))
                .toList();

            // A method that takes the arguments as they are wins over one
            // that takes some of them as the elements of a variable arity
            // parameter
            final var fixed = applicable.stream()
                .filter(candidate -> !expands(candidate, arguments))
                .toList();
            final var candidates = fixed.isEmpty() ? applicable : fixed;

            // The most specific, as Java picks: nullToEmpty(Byte[]) over
            // nullToEmpty(Object[]) for a Byte[]
            final var mostSpecific = candidates.stream()
                .filter(candidate -> candidates.stream().allMatch(other -> isAsSpecific(candidate, other)))
                .toList();

            return mostSpecific.size() == 1 ? mostSpecific.getFirst() : null;
        });
    }

    /**
     * Whether each parameter of the method is the type of the parameter
     * of the other method, or a subtype of it.
     */
    private static boolean isAsSpecific(
        final ResolvedMethodLikeDeclaration method,
        final ResolvedMethodLikeDeclaration other
    ) {
        if (method.getNumberOfParams() != other.getNumberOfParams()) {
            return false;
        }

        for (int i = 0; i < method.getNumberOfParams(); i++) {
            final var type = method.getParam(i).getType();
            final var otherType = other.getParam(i).getType();

            if (!otherType.describe().equals(type.describe()) && !otherType.isAssignableBy(type)) {
                return false;
            }
        }

        return true;
    }

    /**
     * The methods with the name of the call that the type of its scope,
     * or else the class the call is in, declares or inherits; of a method
     * declared again in a subtype, only the one of the subtype.
     */
    private List<ResolvedMethodDeclaration> overloads(final MethodCallExpr call) {
        final var scope = call.getScope().orElse(null);

        final Optional<ResolvedReferenceTypeDeclaration> type = scope == null
            ? call.findAncestor(TypeDeclaration.class).flatMap(declaration -> resolve(declaration::resolve))
            : (scope instanceof MethodCallExpr nested
                // The type the call returns as resolved here, see #method
                ? method(nested).flatMap(declaration -> resolve(declaration::getReturnType))
                : resolve(scope::calculateResolvedType))
                .flatMap(resolved -> resolved.isArray()
                    // Arrays have the methods of Object
                    ? resolve(() -> index.typeSolver().solveType("java.lang.Object"))
                    : resolved.isReferenceType() ? resolved.asReferenceType().getTypeDeclaration() : Optional.empty())
                .or(() -> isTypeName(scope)
                    ? resolve(() -> index.typeSolver().solveType(resolver.resolveName(scope.toString(), call)))
                    : Optional.empty());

        return type
            .map(declaration -> overloads.computeIfAbsent(
                declaration.getQualifiedName() + "#" + call.getNameAsString(),
                key -> overloads(declaration, call.getNameAsString())
            ))
            .orElseGet(List::of);
    }

    private static List<ResolvedMethodDeclaration> overloads(
        final ResolvedReferenceTypeDeclaration type,
        final String name
    ) {
        final var methods = resolve(() -> type.getAllMethods()
            .stream()
            .map(MethodUsage::getDeclaration)
            .filter(candidate -> candidate.getName().equals(name))
            .toList())
            .orElseGet(List::of);

        return methods.stream()
            .filter(candidate -> methods.stream().noneMatch(other -> other != candidate && overrides(other, candidate)))
            .toList();
    }

    /**
     * Whether the method has the parameters of the other method and is
     * declared in a subtype of the type that declares the other.
     */
    private static boolean overrides(
        final ResolvedMethodDeclaration method,
        final ResolvedMethodDeclaration other
    ) {
        return resolve(() -> method.getNumberOfParams() == other.getNumberOfParams()
            && IntStream.range(0, method.getNumberOfParams())
                .allMatch(i -> method.getParam(i).getType().describe().equals(other.getParam(i).getType().describe()))
            && !method.declaringType().getQualifiedName().equals(other.declaringType().getQualifiedName())
            && other.declaringType().isAssignableBy(method.declaringType()))
            .orElse(false);
    }

    /**
     * The types of the arguments of the call; empty for an argument whose
     * type cannot be resolved. A call among them has the return type of
     * the method it resolves to here, see {@link #method}.
     */
    private List<Optional<ResolvedType>> argumentTypes(final MethodCallExpr call) {
        return call.getArguments()
            .stream()
            .map(argument -> argument instanceof MethodCallExpr nested
                ? method(nested)
                    .flatMap(declaration -> resolve(declaration::getReturnType))
                    // A generic method returns what its arguments decide
                    .filter(type -> !type.isTypeVariable() && !(type.isArray() && type.asArrayType().getComponentType().isTypeVariable()))
                    .or(() -> resolve(argument::calculateResolvedType))
                : resolve(argument::calculateResolvedType))
            .toList();
    }

    /**
     * Whether the symbol solver may have resolved the call wrongly: the
     * method has an array parameter, or an argument of the call may be an
     * array. Only then are the types of the arguments checked.
     */
    private static boolean isSuspect(
        final ResolvedMethodLikeDeclaration declaration,
        final MethodCallExpr call
    ) {
        for (int i = 0; i < declaration.getNumberOfParams(); i++) {
            if (declaration.getParam(i).getType().isArray()) {
                return true;
            }
        }

        return call.getArguments().stream().anyMatch(argument -> argument.isArrayCreationExpr()
            || argument.isNameExpr() || argument.isFieldAccessExpr() || argument.isMethodCallExpr()
            || argument.isCastExpr());
    }

    /**
     * Whether the method takes an array argument as a parameter that is
     * not an array, such as {@code Object}, which the symbol solver picks
     * when it wrongly finds the overload for the array ambiguous.
     */
    private static boolean takesArrayAsObject(
        final ResolvedMethodLikeDeclaration declaration,
        final List<Optional<ResolvedType>> arguments
    ) {
        for (int i = 0; i < Math.min(declaration.getNumberOfParams(), arguments.size()); i++) {
            final var argument = arguments.get(i);

            final var parameter = declaration.getParam(i).getType();

            // A type variable, as in arraycopy(T source, ...), takes arrays
            if (argument.isPresent() && argument.get().isArray() && !parameter.isArray() && !parameter.isTypeVariable()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether the method takes the arguments whose types are known. An
     * array of a primitive type is only taken by an array of that type, as
     * Java does not widen or box the elements of an array, and an array of
     * objects by an array of a subtype.
     *
     * @param all whether to check the arguments that are not arrays as
     *            well, or only the arrays the symbol solver gets wrong
     */
    private static boolean accepts(
        final ResolvedMethodLikeDeclaration declaration,
        final List<Optional<ResolvedType>> arguments,
        final boolean all
    ) {
        final var parameters = declaration.getNumberOfParams();
        final var variadic = declaration.hasVariadicParameter();

        if (variadic ? arguments.size() < parameters - 1 : arguments.size() != parameters) {
            return false;
        }

        final var elements = expands(declaration, arguments);

        for (int i = 0; i < arguments.size(); i++) {
            final var parameter = elements && i >= parameters - 1
                ? declaration.getParam(parameters - 1).getType().asArrayType().getComponentType()
                : declaration.getParam(i).getType();
            final var argument = arguments.get(i);

            if (argument.isPresent() && !fits(parameter, argument.get(), all)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Whether the arguments of the variable arity parameter of the method
     * are its elements, rather than a single array passed for it.
     */
    private static boolean expands(
        final ResolvedMethodLikeDeclaration declaration,
        final List<Optional<ResolvedType>> arguments
    ) {
        return declaration.hasVariadicParameter()
            && !(arguments.size() == declaration.getNumberOfParams()
                && arguments.getLast().map(ResolvedType::isArray).orElse(false));
    }

    private static boolean fits(
        final ResolvedType parameter,
        final ResolvedType argument,
        final boolean all
    ) {
        if (parameter.isArray() && argument.isArray()) {
            final var expected = parameter.asArrayType().getComponentType();
            final var actual = argument.asArrayType().getComponentType();

            if (expected.isPrimitive() || actual.isPrimitive()) {
                return expected.describe().equals(actual.describe());
            }

            return expected.isTypeVariable() || resolve(() -> parameter.isAssignableBy(argument)).orElse(false);
        }

        // Type variables take what their bounds allow, which is left to
        // the symbol solver
        if (!all || parameter.isTypeVariable() || parameter.isArray() && parameter.asArrayType().getComponentType().isTypeVariable()) {
            return true;
        }

        return resolve(() -> parameter.isAssignableBy(argument)).orElse(false);
    }

    /**
     * The method a call refers to when the symbol solver cannot resolve
     * it, but its class is in the scan target: the only method of that
     * class with its name that takes as many arguments. The solver fails
     * whenever the type of an argument comes from a library whose jar is
     * not available.
     * <p>
     * A call without a scope, or on {@code this}, looks in the classes
     * enclosing it, innermost first; a call on a variable, a field or a
     * class name looks in the methods its type declares.
     */
    private Optional<String> byArity(final MethodCallExpr call) {
        final var name = call.getNameAsString();
        final var arguments = call.getArguments().size();
        final var scope = call.getScope().orElse(null);

        if (scope != null && !scope.isThisExpr()) {
            return scopeType(scope).flatMap(type -> only(type.getMethodsByName(name), arguments));
        }

        for (Node current = call.getParentNode().orElse(null); current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?> type && !type.getMethodsByName(name).isEmpty()) {
                return only(type.getMethodsByName(name), arguments);
            }
        }

        return Optional.empty();
    }

    /**
     * The only constructor of the class that takes as many arguments,
     * when the class is in the scan target; see {@link #byArity}.
     */
    private Optional<String> constructorByArity(
        final Optional<TypeDeclaration<?>> type,
        final int arguments
    ) {
        return type.flatMap(declaration -> only(declaration.getConstructors(), arguments));
    }

    private Optional<String> only(
        final List<? extends CallableDeclaration<?>> callables,
        final int arguments
    ) {
        final var candidates = callables.stream()
            .filter(callable -> accepts(callable.getParameters(), arguments))
            .toList();

        return candidates.size() == 1 ? declaredName(candidates.getFirst()) : Optional.empty();
    }

    /**
     * The declaration of the type of the scope of a call: of a variable or
     * field, or of the class named for a static call.
     */
    private Optional<TypeDeclaration<?>> scopeType(final Expression scope) {
        return resolve(scope::calculateResolvedType)
            .filter(ResolvedType::isReferenceType)
            .flatMap(type -> type.asReferenceType().getTypeDeclaration())
            .flatMap(JavaBodyParser::declaration)
            .or(() -> isTypeName(scope)
                ? declaration(resolver.resolveName(scope.toString(), scope))
                : Optional.empty());
    }

    /**
     * The declaration of the class in the scan target, or nothing for a
     * class of the JDK or a library.
     */
    private Optional<TypeDeclaration<?>> declaration(final ClassOrInterfaceType type) {
        return declaration(resolver.resolveName(type.getNameWithScope(), type));
    }

    private Optional<TypeDeclaration<?>> declaration(final String qualifiedName) {
        return resolve(() -> index.typeSolver().tryToSolveType(qualifiedName))
            .filter(reference -> reference.isSolved())
            .flatMap(reference -> declaration(reference.getCorrespondingDeclaration()));
    }

    private static Optional<TypeDeclaration<?>> declaration(final ResolvedReferenceTypeDeclaration type) {
        return resolve(type::toAst)
            .flatMap(node -> node)
            .filter(TypeDeclaration.class::isInstance)
            .map(node -> (TypeDeclaration<?>) node);
    }

    /**
     * The class whose constructor {@code this(...)} or {@code super(...)}
     * invokes.
     */
    private Optional<TypeDeclaration<?>> invoked(final ExplicitConstructorInvocationStmt invocation) {
        final Optional<TypeDeclaration<?>> type = invocation.findAncestor(TypeDeclaration.class)
            .map(declaration -> (TypeDeclaration<?>) declaration);

        if (invocation.isThis()) {
            return type;
        }

        return type
            .filter(ClassOrInterfaceDeclaration.class::isInstance)
            .flatMap(declaration -> ((ClassOrInterfaceDeclaration) declaration).getExtendedTypes().stream().findFirst())
            .flatMap(this::declaration);
    }

    /**
     * Whether the expression names a class, e.g. {@code Orders} or
     * {@code com.example.Orders}, going by the Java naming conventions, as
     * the parser cannot tell a class from a variable.
     */
    private static boolean isTypeName(final Expression expression) {
        final var name = expression.toString();
        final var simpleName = name.substring(name.lastIndexOf('.') + 1);

        return (expression.isNameExpr() || expression.isFieldAccessExpr())
            && !simpleName.isEmpty()
            && Character.isUpperCase(simpleName.charAt(0));
    }

    private static boolean accepts(
        final NodeList<com.github.javaparser.ast.body.Parameter> parameters,
        final int arguments
    ) {
        return parameters.isNonEmpty() && parameters.get(parameters.size() - 1).isVarArgs()
            ? arguments >= parameters.size() - 1
            : arguments == parameters.size();
    }

    /**
     * Adds the access when the name or field access expression refers to
     * a field or an enum constant, rather than to a variable, a type or a
     * package.
     */
    private void fieldAccess(
        final Method method,
        final Expression expression,
        final Supplier<? extends ResolvedValueDeclaration> resolve
    ) {
        resolve(() -> fieldName(resolve.get())).flatMap(target -> target).ifPresent(target -> {
            final var access = new FieldAccess();

            access.setTarget(target);
            access.setAccess(access(expression));

            JavaLocations.locate(expression, access);
            method.getFieldAccesses().add(access);
        });
    }

    private static Optional<String> fieldName(final ResolvedValueDeclaration declaration) {
        if (declaration.isField()) {
            final var field = declaration.asField();

            return Optional.of(field.declaringType().getQualifiedName() + "." + field.getName());
        }

        if (declaration.isEnumConstant() && declaration.getType().isReferenceType()) {
            return Optional.of(declaration.getType().asReferenceType().getQualifiedName() + "." + declaration.getName());
        }

        return Optional.empty();
    }

    private static FieldAccess.Access access(final Expression expression) {
        return switch (expression.getParentNode().orElse(null)) {
            case AssignExpr assign when assign.getTarget() == expression ->
                assign.getOperator() == AssignExpr.Operator.ASSIGN
                    ? FieldAccess.Access.WRITE
                    : FieldAccess.Access.READ_WRITE;
            case UnaryExpr unary when switch (unary.getOperator()) {
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
                default -> false;
            } -> FieldAccess.Access.READ_WRITE;
            case null, default -> FieldAccess.Access.READ;
        };
    }

    /**
     * One use per class the type names, including its type arguments,
     * e.g. both {@code java.util.List} and {@code com.example.Order} for
     * {@code List<Order>}. Primitive types and type variables are left out.
     */
    private void typeUses(
        final Method method,
        final Type type,
        final TypeUse.Kind kind
    ) {
        type.walk(ClassOrInterfaceType.class, classType -> {
            // The Map of Map.Entry is part of the name of Map.Entry
            if (classType.getParentNode().orElse(null) instanceof ClassOrInterfaceType parent
                && parent.getScope().filter(scope -> scope == classType).isPresent()) {
                return;
            }

            final var name = classType.getNameWithScope();

            if (resolver.isTypeVariable(name, classType)) {
                return;
            }

            final var use = new TypeUse();

            use.setKind(kind);

            resolve(classType::resolve)
                .filter(ResolvedType::isReferenceType)
                .map(resolved -> resolved.asReferenceType().getQualifiedName())
                .ifPresentOrElse(
                    use::setTarget,
                    () -> {
                        use.setTarget(resolver.resolveName(name, classType));
                        use.setResolved(false);
                    }
                );

            JavaLocations.locate(classType, use);
            method.getTypeUses().add(use);
        });
    }

    /**
     * Resolves with the symbol solver, or nothing when the symbol cannot
     * be resolved, e.g. because it is declared in a library.
     */
    private static <T> Optional<T> resolve(final Supplier<T> resolve) {
        try {
            return Optional.ofNullable(resolve.get());
        } catch (RuntimeException | StackOverflowError e) {
            return Optional.empty();
        }
    }

    /**
     * The qualified name of the method or constructor, as its declaration
     * is named in the scan result. Methods declared in the scan target are
     * named from their declaration; methods of the JDK from their
     * resolved signature, in the same format.
     */
    private String qualifiedName(final ResolvedMethodLikeDeclaration declaration) {
        final var callable = resolve(declaration::toAst)
            .flatMap(node -> node)
            .filter(CallableDeclaration.class::isInstance)
            .map(node -> (CallableDeclaration<?>) node);

        if (callable.isEmpty()) {
            return resolvedName(declaration);
        }

        // A method of a local or anonymous class has no name in the scan
        // result, which leaves those classes out
        return declaredName(callable.get()).orElseThrow(() -> new IllegalStateException(
            "Declared in a local or anonymous class: " + declaration.getName()
        ));
    }

    private Optional<String> declaredName(final CallableDeclaration<?> callable) {
        if (!(callable.getParentNode().orElse(null) instanceof TypeDeclaration<?> type)
            || !JavaClassParser.isNamed(type)) {
            return Optional.empty();
        }

        return callable.findCompilationUnit().map(compilationUnit -> {
            final var declaringResolver = resolvers.computeIfAbsent(
                compilationUnit,
                unit -> new JavaTypeResolver(unit, index)
            );

            return JavaClassParser.qualifiedName(
                declaringResolver,
                declaringResolver.qualifiedName(type),
                callable.getNameAsString(),
                callable.getParameters()
            );
        });
    }

    private static String resolvedName(final ResolvedMethodLikeDeclaration declaration) {
        final var parameters = new StringBuilder();

        for (int i = 0; i < declaration.getNumberOfParams(); i++) {
            final var parameter = declaration.getParam(i);
            final var type = parameter.getType();

            parameters.append(i == 0 ? "" : ", ");
            parameters.append(
                parameter.isVariadic() && type.isArray()
                    ? name(type.asArrayType().getComponentType()) + "..."
                    : name(type)
            );
        }

        return declaration.declaringType().getQualifiedName() + "." + declaration.getName() + "(" + parameters + ")";
    }

    /**
     * The type in the format of {@link JavaTypeResolver#resolve}, e.g.
     * {@code java.util.Map<java.lang.String,K>}.
     */
    private static String name(final ResolvedType type) {
        if (type.isArray()) {
            return name(type.asArrayType().getComponentType()) + "[]";
        }

        if (type.isReferenceType()) {
            final var reference = type.asReferenceType();

            return reference.getQualifiedName()
                + (reference.isRawType() || reference.typeParametersValues().isEmpty()
                    ? ""
                    : reference.typeParametersValues()
                        .stream()
                        .map(JavaBodyParser::name)
                        .collect(Collectors.joining(",", "<", ">")));
        }

        if (type.isWildcard()) {
            final var wildcard = type.asWildcard();

            return !wildcard.isBounded()
                ? "?"
                : (wildcard.isExtends() ? "? extends " : "? super ") + name(wildcard.getBoundedType());
        }

        // Primitives, void and type variables
        return type.describe();
    }

    /**
     * The method as called in the source, with its scope when that is a
     * name, e.g. {@code log.info} or {@code this.orders.save}.
     */
    private static String writtenName(final MethodCallExpr call) {
        return call.getScope()
            .filter(scope -> scope.isNameExpr() || scope.isFieldAccessExpr() || scope.isThisExpr())
            .map(scope -> scope + "." + call.getNameAsString())
            .orElseGet(call::getNameAsString);
    }

    /**
     * The constructor of the class, named after it, e.g.
     * {@code org.example.Client.Client}, without parameter types as its
     * declaration is unknown.
     */
    private String constructorName(final ClassOrInterfaceType type) {
        return resolver.resolveName(type.getNameWithScope(), type) + "." + type.getNameAsString();
    }

    private String writtenName(final ExplicitConstructorInvocationStmt invocation) {
        final var type = invocation.findAncestor(ClassOrInterfaceDeclaration.class);

        if (invocation.isThis()) {
            return type
                .map(declaration -> resolver.qualifiedName(declaration) + "." + declaration.getNameAsString())
                .orElse("this");
        }

        return type
            .flatMap(declaration -> declaration.getExtendedTypes().stream().findFirst())
            .map(this::constructorName)
            .orElse("super");
    }

    /**
     * The referenced method, qualified by its type when the reference
     * names one, e.g. {@code com.example.Order.getId} for
     * {@code Order::getId}, or the constructor for {@code Order::new}.
     */
    private String writtenName(final MethodReferenceExpr reference) {
        // The parser takes the scope of orders::add for a type as well
        if (reference.getScope() instanceof TypeExpr typeExpr
            && typeExpr.getType() instanceof ClassOrInterfaceType type
            && Character.isUpperCase(type.getNameAsString().charAt(0))) {
            return resolver.resolveName(type.getNameWithScope(), type) + "."
                + (reference.getIdentifier().equals("new") ? type.getNameAsString() : reference.getIdentifier());
        }

        return reference.getIdentifier();
    }
}
