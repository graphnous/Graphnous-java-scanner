/*
 * Copyright (C) 2015-2016 Federico Tomassetti
 * Copyright (C) 2017-2026 The JavaParser Team.
 *
 * This file is part of JavaParser.
 *
 * JavaParser can be used either under the terms of
 * a) the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * b) the terms of the Apache License
 *
 * You should have received a copy of both licenses in LICENCE.LGPL and
 * LICENCE.APACHE. Please refer to those files for details.
 *
 * JavaParser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 */

/*
 * Modified by GraphNous: a copy of BlockStmtContext from
 * javaparser-symbol-solver-core 3.28.2, used under the Apache License, that
 * replaces the class of the library. Resolving a name in a block looked up
 * the position of the statement with Node.equals, which compares the whole
 * subtree, and then asked every statement before it for the pattern
 * variables it introduces. Both took time in proportion to the size of the
 * block, for each statement before the one a name is resolved in, so
 * resolving the names of a long method, such as a test with thousands of
 * statements, took minutes. This copy skips the pattern variables of a
 * block that has none, and keeps the positions of the statements by their
 * identity, with the same results.
 *
 * Remove it once JavaParser resolves names in time independent of the
 * size of the block; check the version the pom uses against the one above.
 */

package com.github.javaparser.symbolsolver.javaparsermodel.contexts;

import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class BlockStmtContext extends StatementContext<BlockStmt> {

    /**
     * Whether the block has a type pattern anywhere, kept on the block as
     * a new context is created for every name resolved in it.
     */
    private static final DataKey<Boolean> HAS_TYPE_PATTERNS = new DataKey<Boolean>() {};

    /**
     * The positions of the statements of the block by their identity, kept
     * on the block like {@link #HAS_TYPE_PATTERNS}.
     */
    private static final DataKey<Map<Node, Integer>> POSITIONS = new DataKey<Map<Node, Integer>>() {};

    public BlockStmtContext(BlockStmt wrappedNode, TypeSolver typeSolver) {
        super(wrappedNode, typeSolver);
    }

    @Override
    public List<VariableDeclarator> localVariablesExposedToChild(Node child) {
        int position = positionOf(child);
        if (position == -1) {
            throw new RuntimeException();
        }
        List<VariableDeclarator> variableDeclarators = new LinkedList<>();
        for (int i = position - 1; i >= 0; i--) {
            variableDeclarators.addAll(localVariablesDeclaredIn(wrappedNode.getStatement(i)));
        }
        return variableDeclarators;
    }

    private List<VariableDeclarator> localVariablesDeclaredIn(Statement statement) {
        if (statement instanceof ExpressionStmt) {
            ExpressionStmt expressionStmt = (ExpressionStmt) statement;
            if (expressionStmt.getExpression() instanceof VariableDeclarationExpr) {
                VariableDeclarationExpr variableDeclarationExpr =
                        (VariableDeclarationExpr) expressionStmt.getExpression();
                List<VariableDeclarator> variableDeclarators = new LinkedList<>();
                variableDeclarators.addAll(variableDeclarationExpr.getVariables());
                return variableDeclarators;
            }
        }
        return Collections.emptyList();
    }

    /**
     * The following rule applies to a block statement S contained in a block that is not a switch block:
     * - A pattern variable introduced by S is definitely matched at all the block statements following S, if any,
     *   in the block.
     *
     * https://docs.oracle.com/javase/specs/jls/se22/html/jls-6.html#jls-6.3.2.1
     */
    @Override
    public List<TypePatternExpr> typePatternExprsExposedToChild(Node child) {
        if (!hasTypePatterns()) {
            return new LinkedList<>();
        }
        int position = positionOf(child);
        if (position == -1) {
            throw new RuntimeException();
        }
        List<TypePatternExpr> patternExprs = new LinkedList<>();
        for (int i = position - 1; i >= 0; i--) {
            Context context = JavaParserFactory.getContext(wrappedNode.getStatement(i), typeSolver);
            if (!(context instanceof StatementContext)) {
                throw new IllegalStateException("Got non-statement context for statement");
            }
            List<TypePatternExpr> introducedPatterns = ((StatementContext<?>) context).getIntroducedTypePatterns();
            patternExprs.addAll(introducedPatterns);
        }
        return patternExprs;
    }

    /**
     * The position of the statement in the block: found by identity, as
     * the child is one of its statements, and else by equality as the
     * library does.
     */
    private int positionOf(Node child) {
        NodeList<Statement> statements = wrappedNode.getStatements();
        Map<Node, Integer> positions = wrappedNode.containsData(POSITIONS) ? wrappedNode.getData(POSITIONS) : null;
        // Rebuilt when the statements change, which resolving does not do
        if (positions == null || positions.size() != statements.size()) {
            positions = new IdentityHashMap<>();
            for (int i = 0; i < statements.size(); i++) {
                positions.put(statements.get(i), i);
            }
            wrappedNode.setData(POSITIONS, positions);
        }
        Integer position = positions.get(child);
        if (position != null && statements.get(position) == child) {
            return position;
        }
        return statements.indexOf(child);
    }

    private boolean hasTypePatterns() {
        if (!wrappedNode.containsData(HAS_TYPE_PATTERNS)) {
            wrappedNode.setData(
                    HAS_TYPE_PATTERNS,
                    wrappedNode.findFirst(TypePatternExpr.class).isPresent());
        }
        return wrappedNode.getData(HAS_TYPE_PATTERNS);
    }
}
