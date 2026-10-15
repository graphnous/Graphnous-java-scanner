package dev.graphnous.scanner;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.graphnous.scanner.model.Annotation;
import dev.graphnous.scanner.model.Arguments;

import java.util.List;

/**
 * Turns the annotations of a declaration into the scan result model, with
 * their arguments as JSON values:
 * <ul>
 *     <li>literals as strings, numbers and booleans,</li>
 *     <li>arrays as lists,</li>
 *     <li>class literals as the qualified type plus {@code .class}, e.g.
 *     {@code java.lang.String.class},</li>
 *     <li>constants of the scanned sources, and expressions of them, as
 *     their values, e.g. {@code /api/orders} for {@code "/api" + Paths.ORDERS},
 *     see {@link JavaConstants},</li>
 *     <li>other constants, such as enum values, as qualified references,
 *     e.g. {@code org.example.Method.GET},</li>
 *     <li>nested annotations as annotations,</li>
 *     <li>anything else, such as {@code "/api" + PATH}, as its source.</li>
 * </ul>
 * The argument of a single-member annotation such as {@code @Path("/orders")}
 * is named {@code value}, as it is in Java. Marker annotations have no
 * arguments.
 */
class JavaAnnotationParser {

    private final JavaTypeResolver resolver;

    JavaAnnotationParser(final JavaTypeResolver resolver) {
        this.resolver = resolver;
    }

    List<Annotation> annotations(final NodeList<AnnotationExpr> expressions) {
        return expressions.stream()
            .map(this::annotation)
            .toList();
    }

    private Annotation annotation(final AnnotationExpr expression) {
        final var annotation = new Annotation();

        annotation.setName(
            expression.getName().getIdentifier()
        );

        annotation.setQualifiedName(
            resolver.resolveName(expression.getNameAsString(), expression)
        );

        JavaLocations.locate(expression, annotation);

        switch (expression) {
            case SingleMemberAnnotationExpr single -> {
                final var arguments = new Arguments();
                arguments.setAdditionalProperty("value", value(single.getMemberValue()));
                annotation.setArguments(arguments);
            }
            case NormalAnnotationExpr normal when normal.getPairs().isNonEmpty() -> {
                final var arguments = new Arguments();
                normal.getPairs().forEach(pair ->
                    arguments.setAdditionalProperty(pair.getNameAsString(), value(pair.getValue()))
                );
                annotation.setArguments(arguments);
            }
            default -> {
            }
        }

        return annotation;
    }

    /**
     * The value of an annotation argument, or of the default of an
     * annotation element, as a JSON value.
     */
    Object value(final Expression expression) {
        return switch (expression) {
            case StringLiteralExpr string -> string.asString();
            case TextBlockLiteralExpr textBlock -> textBlock.asString();
            case CharLiteralExpr character -> String.valueOf(character.asChar());
            case BooleanLiteralExpr bool -> bool.getValue();
            case IntegerLiteralExpr integer -> integer.asNumber();
            case LongLiteralExpr longValue -> longValue.asNumber();
            case DoubleLiteralExpr doubleValue -> doubleValue.asDouble();
            case UnaryExpr unary when unary.getOperator() == UnaryExpr.Operator.MINUS
                && value(unary.getExpression()) instanceof Number number -> negate(number);
            case ArrayInitializerExpr array -> array.getValues()
                .stream()
                .map(this::value)
                .toList();
            case ClassExpr classExpr -> resolver.resolve(classExpr.getType(), classExpr) + ".class";
            case AnnotationExpr annotation -> annotation(annotation);
            default -> {
                final var constant = constant(expression);

                if (constant != null) {
                    yield constant;
                }

                if (expression instanceof FieldAccessExpr access
                    && (access.getScope().isNameExpr() || access.getScope().isFieldAccessExpr())) {
                    yield resolver.resolveName(access.getScope().toString(), access) + "." + access.getNameAsString();
                }

                yield expression.toString();
            }
        };
    }

    /**
     * The value of a constant expression, such as {@code Topics.CREATED}
     * or {@code "/api" + PATH}, as a JSON value: a char as a string; null
     * when it is none, or when it holds a secret.
     */
    private static Object constant(final Expression expression) {
        return JavaConstants.value(expression)
            .map(value -> value instanceof Character character ? String.valueOf(character) : value)
            .filter(value -> !(value instanceof String text) || !Secrets.holdsSecret(text))
            .orElse(null);
    }

    private static Number negate(final Number number) {
        return switch (number) {
            case Integer integer -> -integer;
            case Long longValue -> -longValue;
            case Double doubleValue -> -doubleValue;
            default -> number;
        };
    }
}
