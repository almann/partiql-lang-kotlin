package org.partiql.lang.eval.physical

import com.amazon.ionelement.api.BoolElement
import com.amazon.ionelement.api.MetaContainer
import org.partiql.lang.ast.SourceLocationMeta
import org.partiql.lang.domains.PartiqlPhysical
import org.partiql.lang.eval.ExprValueFactory
import org.partiql.lang.eval.Thunk
import org.partiql.lang.eval.ThunkValue
import org.partiql.lang.eval.physical.operators.FilterRelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.JoinRelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.LetRelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.LimitRelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.OffsetRelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.ProjectRelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.RelationExpression
import org.partiql.lang.eval.physical.operators.RelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.RelationalOperatorFactoryKey
import org.partiql.lang.eval.physical.operators.RelationalOperatorKind
import org.partiql.lang.eval.physical.operators.ScanRelationalOperatorFactory
import org.partiql.lang.eval.physical.operators.VariableBinding
import org.partiql.lang.eval.physical.operators.valueExpression
import org.partiql.lang.util.toIntExact

/** A specialization of [Thunk] that we use for evaluation of physical plans. */
internal typealias PhysicalPlanThunk = Thunk<EvaluatorState>

/** A specialization of [ThunkValue] that we use for evaluation of physical plans. */
internal typealias PhysicalPlanThunkValue<T> = ThunkValue<EvaluatorState, T>

internal class PhysicalBexprToThunkConverter(
    private val exprConverter: PhysicalExprToThunkConverter,
    private val valueFactory: ExprValueFactory,
    private val relationalOperatorFactory: Map<RelationalOperatorFactoryKey, RelationalOperatorFactory>
) : PartiqlPhysical.Bexpr.Converter<RelationThunkEnv> {

    private fun PhysicalPlanThunk.toValueExpr(sourceLocationMeta: SourceLocationMeta?) =
        valueExpression(sourceLocationMeta) { state -> this(state) }

    private fun RelationExpression.toRelationThunk(metas: MetaContainer) = relationThunk(metas) { state -> this.evaluate(state) }

    private inline fun <reified T : RelationalOperatorFactory> findOperatorFactory(
        operator: RelationalOperatorKind,
        name: String
    ): T {
        val key = RelationalOperatorFactoryKey(operator, name)
        val found =
            relationalOperatorFactory[key] ?: error("Factory for operator ${key.operator} named '${key.name}' does not exist.")
        return found as? T
            ?: error(
                "Internal error: Operator factory ${key.operator} named '${key.name}' does not derive from " +
                    T::class.java + "."
            )
    }

    override fun convertProject(node: PartiqlPhysical.Bexpr.Project): RelationThunkEnv {
        // recurse into children
        val argExprs = node.args.map { exprConverter.convert(it).toValueExpr(it.metas.sourceLocationMeta) }

        // locate operator factory
        val factory = findOperatorFactory<ProjectRelationalOperatorFactory>(RelationalOperatorKind.PROJECT, node.i.name.text)

        // create operator implementation
        val bindingsExpr = factory.create(node.i, node.binding.toSetVariableFunc(), argExprs)

        // wrap in thunk.
        return bindingsExpr.toRelationThunk(node.metas)
    }

    override fun convertScan(node: PartiqlPhysical.Bexpr.Scan): RelationThunkEnv {
        // recurse into children
        val valueExpr = exprConverter.convert(node.expr).toValueExpr(node.expr.metas.sourceLocationMeta)
        val asSetter = node.asDecl.toSetVariableFunc()
        val atSetter = node.atDecl?.toSetVariableFunc()
        val bySetter = node.byDecl?.toSetVariableFunc()

        // locate operator factory
        val factory = findOperatorFactory<ScanRelationalOperatorFactory>(RelationalOperatorKind.SCAN, node.i.name.text)

        // create operator implementation
        val bindingsExpr = factory.create(
            impl = node.i,
            expr = valueExpr,
            setAsVar = asSetter,
            setAtVar = atSetter,
            setByVar = bySetter
        )

        // wrap in thunk
        return bindingsExpr.toRelationThunk(node.metas)
    }

    override fun convertFilter(node: PartiqlPhysical.Bexpr.Filter): RelationThunkEnv {
        // recurse into children
        val predicateValueExpr = exprConverter.convert(node.predicate).toValueExpr(node.predicate.metas.sourceLocationMeta)
        val sourceBindingsExpr = this.convert(node.source)

        // locate operator factory
        val factory = findOperatorFactory<FilterRelationalOperatorFactory>(RelationalOperatorKind.FILTER, node.i.name.text)

        // create operator implementation
        val bindingsExpr = factory.create(node.i, predicateValueExpr, sourceBindingsExpr)

        // wrap in thunk
        return bindingsExpr.toRelationThunk(node.metas)
    }

    override fun convertJoin(node: PartiqlPhysical.Bexpr.Join): RelationThunkEnv {
        // recurse into children
        val leftBindingsExpr = this.convert(node.left)
        val rightBindingdExpr = this.convert(node.right)
        val predicateValueExpr = node.predicate?.let {
            exprConverter.convert(it)
                .takeIf { !node.predicate.isLitTrue() }
                ?.toValueExpr(it.metas.sourceLocationMeta)
        }

        // locate operator factory
        val factory = findOperatorFactory<JoinRelationalOperatorFactory>(RelationalOperatorKind.JOIN, node.i.name.text)

        // Compute a function to set the left-side variables to NULL.  This is for use with RIGHT JOIN, when the left
        // side of the join is empty or no rows match the predicate.
        val leftVariableIndexes = node.left.extractAccessibleVarDecls().map { it.index.value.toIntExact() }
        val setLeftSideVariablesToNull: (EvaluatorState) -> Unit = { state ->
            leftVariableIndexes.forEach { state.registers[it] = valueFactory.nullValue }
        }
        // Compute a function to set the right-side variables to NULL.  This is for use with LEFT JOIN, when the right
        // side of the join is empty or no rows match the predicate.
        val rightVariableIndexes = node.right.extractAccessibleVarDecls().map { it.index.value.toIntExact() }
        val setRightSideVariablesToNull: (EvaluatorState) -> Unit = { state ->
            rightVariableIndexes.forEach { state.registers[it] = valueFactory.nullValue }
        }

        return factory.create(
            impl = node.i,
            joinType = node.joinType,
            leftBexpr = leftBindingsExpr,
            rightBexpr = rightBindingdExpr,
            predicateExpr = predicateValueExpr,
            setLeftSideVariablesToNull = setLeftSideVariablesToNull,
            setRightSideVariablesToNull = setRightSideVariablesToNull
        ).toRelationThunk(node.metas)
    }

    private fun PartiqlPhysical.Bexpr.extractAccessibleVarDecls(): List<PartiqlPhysical.VarDecl> =
        // This fold traverses a [PartiqlPhysical.Bexpr] node and extracts all variable declarations within
        // It avoids recursing into sub-queries.
        object : PartiqlPhysical.VisitorFold<List<PartiqlPhysical.VarDecl>>() {
            override fun visitVarDecl(
                node: PartiqlPhysical.VarDecl,
                accumulator: List<PartiqlPhysical.VarDecl>
            ): List<PartiqlPhysical.VarDecl> = accumulator + node

            /**
             * Avoids recursion into expressions, since these may contain sub-queries with other var-decls that we don't
             * care about here.
             */
            override fun walkExpr(
                node: PartiqlPhysical.Expr,
                accumulator: List<PartiqlPhysical.VarDecl>
            ): List<PartiqlPhysical.VarDecl> {
                return accumulator
            }
        }.walkBexpr(this, emptyList())

    override fun convertOffset(node: PartiqlPhysical.Bexpr.Offset): RelationThunkEnv {
        // recurse into children
        val rowCountExpr = exprConverter.convert(node.rowCount).toValueExpr(node.rowCount.metas.sourceLocationMeta)
        val sourceBexpr = this.convert(node.source)

        // locate operator factory
        val factory = findOperatorFactory<OffsetRelationalOperatorFactory>(RelationalOperatorKind.OFFSET, node.i.name.text)

        // create operator implementation
        val bindingsExpr = factory.create(node.i, rowCountExpr, sourceBexpr)
        // wrap in thunk
        return bindingsExpr.toRelationThunk(node.metas)
    }

    override fun convertLimit(node: PartiqlPhysical.Bexpr.Limit): RelationThunkEnv {
        // recurse into children
        val rowCountExpr = exprConverter.convert(node.rowCount).toValueExpr(node.rowCount.metas.sourceLocationMeta)
        val sourceBexpr = this.convert(node.source)

        // locate operator factory
        val factory = findOperatorFactory<LimitRelationalOperatorFactory>(RelationalOperatorKind.LIMIT, node.i.name.text)

        // create operator implementation
        val bindingsExpr = factory.create(node.i, rowCountExpr, sourceBexpr)

        // wrap in thunk
        return bindingsExpr.toRelationThunk(node.metas)
    }

    override fun convertLet(node: PartiqlPhysical.Bexpr.Let): RelationThunkEnv {
        // recurse into children
        val sourceBexpr = this.convert(node.source)
        val compiledBindings = node.bindings.map {
            VariableBinding(
                it.decl.toSetVariableFunc(),
                exprConverter.convert(it.value).toValueExpr(it.value.metas.sourceLocationMeta)
            )
        }
        // locate operator factory
        val factory = findOperatorFactory<LetRelationalOperatorFactory>(RelationalOperatorKind.LET, node.i.name.text)

        // create operator implementation
        val bindingsExpr = factory.create(node.i, sourceBexpr, compiledBindings)

        // wrap in thunk
        return bindingsExpr.toRelationThunk(node.metas)
    }
}

private fun PartiqlPhysical.Expr.isLitTrue() =
    this is PartiqlPhysical.Expr.Lit && this.value is BoolElement && this.value.booleanValue
