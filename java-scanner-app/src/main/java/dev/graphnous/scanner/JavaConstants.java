package dev.graphnous.scanner;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The values of constant expressions, as the compiler computes them: of
 * literals, of the constants they refer to, and of the operators that
 * combine them, such as {@code "orders." + EVENT}.
 * <p>
 * A constant is a final field or local variable of a primitive type or
 * String whose initializer is a constant expression, as fields of an
 * interface are; only those of the scanned sources are known, not those
 * of libraries. A constant whose name names a secret, see
 * {@link Secrets#namesSecret}, has no value here, nor has an expression
 * that uses one.
 * <p>
 * Values are Strings, Integers, Longs, Doubles, Booleans and Characters.
 */
final class JavaConstants {

    /**
     * The longest text of a constant that is recorded.
     */
    static final int MAX_LENGTH = 1000;

    private JavaConstants() {
    }

    /**
     * The value of the expression, when it is a constant expression.
     */
    static Optional<Object> value(final Expression expression) {
        return Optional.ofNullable(evaluate(expression, Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    /**
     * The value of the expression as the scan result records it: its
     * text, when it is a constant expression of at most
     * {@link #MAX_LENGTH} characters that holds no secret.
     */
    static Optional<String> text(final Expression expression) {
        return value(expression)
            .map(String::valueOf)
            .filter(text -> text.length() <= MAX_LENGTH && !Secrets.holdsSecret(text));
    }

    /**
     * The value of a constant variable, as {@link #text} records it.
     */
    static Optional<String> text(final VariableDeclarator variable) {
        if (!isConstant(variable)) {
            return Optional.empty();
        }

        return Optional.ofNullable(constant(variable, Collections.newSetFromMap(new IdentityHashMap<>())))
            .map(String::valueOf)
            .filter(text -> text.length() <= MAX_LENGTH && !Secrets.holdsSecret(text));
    }

    /**
     * @param evaluating the constants being evaluated, to stop at a cycle,
     *                   as in A = B + "", B = A + ""
     */
    private static Object evaluate(
        final Expression expression,
        final Set<VariableDeclarator> evaluating
    ) {
        return switch (expression) {
            case StringLiteralExpr string -> string.asString();
            case TextBlockLiteralExpr textBlock -> textBlock.asString();
            case CharLiteralExpr character -> character.asChar();
            case BooleanLiteralExpr bool -> bool.getValue();
            case IntegerLiteralExpr integer -> number(integer::asNumber);
            case LongLiteralExpr longValue -> number(longValue::asNumber);
            case DoubleLiteralExpr doubleValue -> doubleValue.asDouble();
            case EnclosedExpr enclosed -> evaluate(enclosed.getInner(), evaluating);
            case CastExpr cast -> cast(cast.getType(), evaluate(cast.getExpression(), evaluating));
            case UnaryExpr unary -> unary(unary.getOperator(), evaluate(unary.getExpression(), evaluating));
            case BinaryExpr binary -> binary(binary, evaluating);
            case ConditionalExpr conditional -> evaluate(conditional.getCondition(), evaluating) instanceof Boolean condition
                ? evaluate(condition ? conditional.getThenExpr() : conditional.getElseExpr(), evaluating)
                : null;
            case NameExpr name -> reference(name::resolve, evaluating);
            case FieldAccessExpr access -> reference(access::resolve, evaluating);
            default -> null;
        };
    }

    private static Object number(final Supplier<Number> number) {
        try {
            return number.get();
        } catch (RuntimeException e) {
            // A literal out of range does not compile
            return null;
        }
    }

    /**
     * The value of the constant a name refers to.
     */
    private static Object reference(
        final Supplier<? extends ResolvedValueDeclaration> resolve,
        final Set<VariableDeclarator> evaluating
    ) {
        final ResolvedValueDeclaration declaration;

        try {
            declaration = resolve.get();
        } catch (RuntimeException | StackOverflowError e) {
            // Unknown, as a constant of a library or a missing class
            return null;
        }

        final var variable = variable(declaration);

        if (variable == null || !isConstant(variable)) {
            return null;
        }

        return constant(variable, evaluating);
    }

    private static Object constant(
        final VariableDeclarator variable,
        final Set<VariableDeclarator> evaluating
    ) {
        if (Secrets.namesSecret(variable.getNameAsString()) || !evaluating.add(variable)) {
            return null;
        }

        try {
            return variable.getInitializer()
                .map(initializer -> convert(variable.getType(), evaluate(initializer, evaluating)))
                .orElse(null);
        } finally {
            evaluating.remove(variable);
        }
    }

    /**
     * The declaration of the field or local variable, when it is in the
     * scanned sources.
     */
    private static VariableDeclarator variable(final ResolvedValueDeclaration declaration) {
        if (declaration instanceof JavaParserFieldDeclaration field) {
            return field.getVariableDeclarator();
        }

        final Optional<Node> node;

        try {
            node = declaration.toAst();
        } catch (RuntimeException e) {
            return null;
        }

        return switch (node.orElse(null)) {
            case VariableDeclarator variable -> variable;
            case VariableDeclarationExpr variables -> variables.getVariables().stream()
                .filter(variable -> variable.getNameAsString().equals(declaration.getName()))
                .findFirst()
                .orElse(null);
            case null, default -> null;
        };
    }

    /**
     * Whether the variable is final, of a primitive type or String, and
     * initialized, as a constant variable is; whether its initializer is
     * a constant expression is found when it is evaluated.
     */
    private static boolean isConstant(final VariableDeclarator variable) {
        if (variable.getInitializer().isEmpty() || !isConstantType(variable.getType())) {
            return false;
        }

        return switch (variable.getParentNode().orElse(null)) {
            case FieldDeclaration field -> field.isFinal() || isInterfaceMember(field);
            case VariableDeclarationExpr local -> local.isFinal();
            case null, default -> false;
        };
    }

    private static boolean isInterfaceMember(final FieldDeclaration field) {
        return switch (field.getParentNode().orElse(null)) {
            case ClassOrInterfaceDeclaration type -> type.isInterface();
            case AnnotationDeclaration ignored -> true;
            case null, default -> false;
        };
    }

    private static boolean isConstantType(final Type type) {
        return type.isPrimitiveType()
            || type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getNameWithScope().matches("(java\\.lang\\.)?String");
    }

    /**
     * The value as the type of its variable holds it, as in
     * {@code long L = 1}.
     */
    private static Object convert(
        final Type type,
        final Object value
    ) {
        return type.isPrimitiveType() ? cast(type, value) : value instanceof String ? value : null;
    }

    private static Object cast(
        final Type type,
        final Object value
    ) {
        if (value == null) {
            return null;
        }

        if (!type.isPrimitiveType()) {
            return value instanceof String && isConstantType(type) ? value : null;
        }

        final var number = value instanceof Character character ? Integer.valueOf(character) : value;

        return switch (type.asPrimitiveType().getType()) {
            case BOOLEAN -> value instanceof Boolean ? value : null;
            case CHAR -> number instanceof Number n ? Character.valueOf((char) n.intValue()) : null;
            case BYTE -> number instanceof Number n ? (int) n.byteValue() : null;
            case SHORT -> number instanceof Number n ? (int) n.shortValue() : null;
            case INT -> number instanceof Number n ? n.intValue() : null;
            case LONG -> number instanceof Number n ? n.longValue() : null;
            case FLOAT, DOUBLE -> number instanceof Number n ? n.doubleValue() : null;
        };
    }

    private static Object unary(
        final UnaryExpr.Operator operator,
        final Object value
    ) {
        final var number = value instanceof Character character ? Integer.valueOf(character) : value;

        return switch (operator) {
            case PLUS -> number instanceof Number ? number : null;
            case MINUS -> switch (number) {
                case Integer i -> -i;
                case Long l -> -l;
                case Double d -> -d;
                case null, default -> null;
            };
            case LOGICAL_COMPLEMENT -> value instanceof Boolean bool ? !bool : null;
            default -> null;
        };
    }

    private static Object binary(
        final BinaryExpr binary,
        final Set<VariableDeclarator> evaluating
    ) {
        final var left = evaluate(binary.getLeft(), evaluating);

        if (left == null) {
            return null;
        }

        final var right = evaluate(binary.getRight(), evaluating);

        if (right == null) {
            return null;
        }

        if (binary.getOperator() == BinaryExpr.Operator.PLUS && (left instanceof String || right instanceof String)) {
            return String.valueOf(left) + right;
        }

        if (left instanceof Boolean a && right instanceof Boolean b) {
            return switch (binary.getOperator()) {
                case AND -> a && b;
                case OR -> a || b;
                case EQUALS -> a.equals(b);
                case NOT_EQUALS -> !a.equals(b);
                default -> null;
            };
        }

        final var a = numeric(left);
        final var b = numeric(right);

        if (a == null || b == null) {
            return null;
        }

        if (a instanceof Double || b instanceof Double) {
            final double x = a.doubleValue();
            final double y = b.doubleValue();

            return switch (binary.getOperator()) {
                case PLUS -> x + y;
                case MINUS -> x - y;
                case MULTIPLY -> x * y;
                case DIVIDE -> x / y;
                case REMAINDER -> x % y;
                default -> null;
            };
        }

        final long x = a.longValue();
        final long y = b.longValue();
        final var isLong = a instanceof Long || b instanceof Long;

        final Long result = switch (binary.getOperator()) {
            case PLUS -> x + y;
            case MINUS -> x - y;
            case MULTIPLY -> x * y;
            case DIVIDE -> y == 0 ? null : x / y;
            case REMAINDER -> y == 0 ? null : x % y;
            default -> null;
        };

        if (result == null) {
            return null;
        }

        return isLong ? result : Integer.valueOf((int) result.longValue());
    }

    /**
     * The number a value is in arithmetic: a char is its code.
     */
    private static Number numeric(final Object value) {
        return switch (value) {
            case Character character -> (int) character;
            case Integer integer -> integer;
            case Long longValue -> longValue;
            case Double doubleValue -> doubleValue;
            default -> null;
        };
    }
}
