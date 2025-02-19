/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 *  You may not use this file except in compliance with the License.
 * A copy of the License is located at:
 *
 *      http://aws.amazon.com/apache2.0/
 *
 *  or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 *  language governing permissions and limitations under the License.
 */
@file:Suppress("DEPRECATION") // We don't need warnings about ExprNode deprecation.

package org.partiql.lang.ast.passes

import org.partiql.lang.ast.Assignment
import org.partiql.lang.ast.AssignmentOp
import org.partiql.lang.ast.CallAgg
import org.partiql.lang.ast.Coalesce
import org.partiql.lang.ast.CreateIndex
import org.partiql.lang.ast.CreateTable
import org.partiql.lang.ast.DataManipulation
import org.partiql.lang.ast.DataManipulationOperation
import org.partiql.lang.ast.DataType
import org.partiql.lang.ast.DateLiteral
import org.partiql.lang.ast.DeleteOp
import org.partiql.lang.ast.DmlOpList
import org.partiql.lang.ast.DropIndex
import org.partiql.lang.ast.DropTable
import org.partiql.lang.ast.Exec
import org.partiql.lang.ast.ExprNode
import org.partiql.lang.ast.FromSource
import org.partiql.lang.ast.FromSourceExpr
import org.partiql.lang.ast.FromSourceJoin
import org.partiql.lang.ast.FromSourceLet
import org.partiql.lang.ast.FromSourceUnpivot
import org.partiql.lang.ast.GroupBy
import org.partiql.lang.ast.GroupByItem
import org.partiql.lang.ast.HasMetas
import org.partiql.lang.ast.Identifier
import org.partiql.lang.ast.InsertOp
import org.partiql.lang.ast.InsertValueOp
import org.partiql.lang.ast.LetBinding
import org.partiql.lang.ast.LetSource
import org.partiql.lang.ast.LetVariables
import org.partiql.lang.ast.Literal
import org.partiql.lang.ast.LiteralMissing
import org.partiql.lang.ast.MetaContainer
import org.partiql.lang.ast.NAry
import org.partiql.lang.ast.NullIf
import org.partiql.lang.ast.OnConflict
import org.partiql.lang.ast.OrderBy
import org.partiql.lang.ast.Parameter
import org.partiql.lang.ast.Path
import org.partiql.lang.ast.PathComponent
import org.partiql.lang.ast.PathComponentExpr
import org.partiql.lang.ast.PathComponentUnpivot
import org.partiql.lang.ast.PathComponentWildcard
import org.partiql.lang.ast.RemoveOp
import org.partiql.lang.ast.ReturningElem
import org.partiql.lang.ast.ReturningExpr
import org.partiql.lang.ast.SearchedCase
import org.partiql.lang.ast.SearchedCaseWhen
import org.partiql.lang.ast.Select
import org.partiql.lang.ast.SelectListItem
import org.partiql.lang.ast.SelectListItemExpr
import org.partiql.lang.ast.SelectListItemProjectAll
import org.partiql.lang.ast.SelectListItemStar
import org.partiql.lang.ast.SelectProjection
import org.partiql.lang.ast.SelectProjectionList
import org.partiql.lang.ast.SelectProjectionPivot
import org.partiql.lang.ast.SelectProjectionValue
import org.partiql.lang.ast.Seq
import org.partiql.lang.ast.SeqType
import org.partiql.lang.ast.SimpleCase
import org.partiql.lang.ast.SimpleCaseWhen
import org.partiql.lang.ast.SortSpec
import org.partiql.lang.ast.Struct
import org.partiql.lang.ast.StructField
import org.partiql.lang.ast.SymbolicName
import org.partiql.lang.ast.TimeLiteral
import org.partiql.lang.ast.Typed
import org.partiql.lang.ast.VariableReference
import org.partiql.lang.util.checkThreadInterrupted

/**
 * Provides a minimal interface for an AST rewriter implementation.
 */
@Deprecated("New rewriters should implement PIG's PartiqlAst.VisitorTransform instead")
interface AstRewriter {
    fun rewriteExprNode(node: ExprNode): ExprNode
}

/**
 * This is the base-class for an AST rewriter which simply makes an exact copy of the original AST.
 * Simple rewrites can be performed by inheritors.
 */
@Deprecated("New rewriters should implement PIG's VisitorTransformBase instead")
open class AstRewriterBase : AstRewriter {

    override fun rewriteExprNode(node: ExprNode): ExprNode {
        checkThreadInterrupted()
        return when (node) {
            is Literal -> rewriteLiteral(node)
            is LiteralMissing -> rewriteLiteralMissing(node)
            is VariableReference -> rewriteVariableReference(node)
            is NAry -> rewriteNAry(node)
            is CallAgg -> rewriteCallAgg(node)
            is Typed -> rewriteTyped(node)
            is Path -> rewritePath(node)
            is SimpleCase -> rewriteSimpleCase(node)
            is SearchedCase -> rewriteSearchedCase(node)
            is Struct -> rewriteStruct(node)
            is Seq -> rewriteSeq(node)
            is Select -> rewriteSelect(node)
            is Parameter -> rewriteParameter(node)
            is DataManipulation -> rewriteDataManipulation(node)
            is CreateTable -> rewriteCreateTable(node)
            is CreateIndex -> rewriteCreateIndex(node)
            is DropTable -> rewriteDropTable(node)
            is DropIndex -> rewriteDropIndex(node)
            is NullIf -> rewriteNullIf(node)
            is Coalesce -> rewriteCoalesce(node)
            is Exec -> rewriteExec(node)
            is DateLiteral -> rewriteDate(node)
            is TimeLiteral -> rewriteTime(node)
        }
    }

    open fun rewriteMetas(itemWithMetas: HasMetas): MetaContainer = itemWithMetas.metas

    open fun rewriteLiteral(node: Literal): ExprNode =
        Literal(node.ionValue, rewriteMetas(node))

    open fun rewriteLiteralMissing(node: LiteralMissing): ExprNode =
        LiteralMissing(rewriteMetas(node))

    open fun rewriteVariableReference(node: VariableReference): ExprNode =
        VariableReference(
            id = node.id,
            case = node.case,
            scopeQualifier = node.scopeQualifier,
            metas = rewriteMetas(node)
        )

    open fun rewriteSeq(node: Seq): ExprNode =
        Seq(
            rewriteSeqType(node.type),
            node.values.map { rewriteExprNode(it) },
            rewriteMetas(node)
        )

    open fun rewriteSeqType(type: SeqType): SeqType = type

    open fun rewriteStruct(node: Struct): ExprNode =
        Struct(
            node.fields.mapIndexed { index, field -> rewriteStructField(field, index) },
            rewriteMetas(node)
        )

    open fun rewriteStructField(field: StructField, index: Int): StructField =
        StructField(
            rewriteExprNode(field.name),
            rewriteExprNode(field.expr)
        )

    open fun rewriteSearchedCase(node: SearchedCase): ExprNode {
        return SearchedCase(
            node.whenClauses.map { rewriteSearchedCaseWhen(it) },
            node.elseExpr?.let { rewriteExprNode(it) },
            rewriteMetas(node)
        )
    }

    open fun rewriteSimpleCase(node: SimpleCase): ExprNode {
        return SimpleCase(
            rewriteExprNode(node.valueExpr),
            node.whenClauses.map { rewriteSimpleCaseWhen(it) },
            node.elseExpr?.let { rewriteExprNode(it) },
            rewriteMetas(node)
        )
    }

    open fun rewritePath(node: Path): ExprNode {
        return Path(
            rewriteExprNode(node.root),
            node.components.map { rewritePathComponent(it) },
            rewriteMetas(node)
        )
    }

    open fun rewriteTyped(node: Typed): ExprNode {
        return Typed(
            node.op,
            rewriteExprNode(node.expr),
            rewriteDataType(node.type),
            rewriteMetas(node)
        )
    }

    open fun rewriteCallAgg(node: CallAgg): ExprNode {
        return CallAgg(
            rewriteExprNode(node.funcExpr),
            node.setQuantifier,
            rewriteExprNode(node.arg),
            rewriteMetas(node)
        )
    }

    open fun rewriteNAry(node: NAry): ExprNode {
        return NAry(
            node.op,
            node.args.map { rewriteExprNode(it) },
            rewriteMetas(node)
        )
    }

    open fun rewriteSelect(selectExpr: Select): ExprNode =
        innerRewriteSelect(selectExpr)

    open fun rewriteNullIf(node: NullIf): ExprNode {
        return NullIf(
            rewriteExprNode(node.expr1),
            rewriteExprNode(node.expr2),
            rewriteMetas(node)
        )
    }

    open fun rewriteCoalesce(node: Coalesce): ExprNode {
        return Coalesce(
            node.args.map { rewriteExprNode(it) },
            rewriteMetas(node)
        )
    }

    /**
     * Many subtypes of [AstRewriterBase] need to override [rewriteSelect] to selectively apply a different nested
     * instance of themselves to [Select] nodes.  These subtypes can invoke this method instead of [rewriteSelect]
     * to avoid infinite recursion.  They can also override this function if they need to customize how the new
     * [Select] node is instantiated.
     *
     * The traversal order is in the SQL semantic order--that is:
     *
     * 1. `FROM`
     * 2. `LET`
     * 3. `WHERE`
     * 4. `GROUP BY`
     * 5. `HAVING`
     * 6. *projection*
     * 7. `ORDER BY` (to be implemented)
     * 8. `OFFSET`
     * 9. `LIMIT`
     */
    protected open fun innerRewriteSelect(selectExpr: Select): Select {
        val from = rewriteFromSource(selectExpr.from)
        val fromLet = selectExpr.fromLet?.let { rewriteLetSource(it) }
        val where = selectExpr.where?.let { rewriteSelectWhere(it) }
        val groupBy = selectExpr.groupBy?.let { rewriteGroupBy(it) }
        val having = selectExpr.having?.let { rewriteSelectHaving(it) }
        val projection = rewriteSelectProjection(selectExpr.projection)
        val orderBy = selectExpr.orderBy?.let { rewriteOrderBy(it) }
        val offset = selectExpr.offset?.let { rewriteSelectOffset(it) }
        val limit = selectExpr.limit?.let { rewriteSelectLimit(it) }
        val metas = rewriteSelectMetas(selectExpr)

        return Select(
            setQuantifier = selectExpr.setQuantifier,
            projection = projection,
            from = from,
            fromLet = fromLet,
            where = where,
            groupBy = groupBy,
            having = having,
            orderBy = orderBy,
            limit = limit,
            offset = offset,
            metas = metas
        )
    }

    open fun rewriteSelectWhere(node: ExprNode): ExprNode = rewriteExprNode(node)

    open fun rewriteSelectHaving(node: ExprNode): ExprNode = rewriteExprNode(node)

    open fun rewriteSelectLimit(node: ExprNode): ExprNode = rewriteExprNode(node)

    open fun rewriteSelectOffset(node: ExprNode): ExprNode = rewriteExprNode(node)

    open fun rewriteSelectMetas(selectExpr: Select): MetaContainer = rewriteMetas(selectExpr)

    open fun rewriteSelectProjection(projection: SelectProjection): SelectProjection =
        when (projection) {
            is SelectProjectionList -> rewriteSelectProjectionList(projection)
            is SelectProjectionValue -> rewriteSelectProjectionValue(projection)
            is SelectProjectionPivot -> rewriteSelectProjectionPivot(projection)
        }

    open fun rewriteSelectProjectionList(projection: SelectProjectionList): SelectProjection =
        SelectProjectionList(
            projection.items.map { it -> rewriteSelectListItem(it) }, rewriteMetas(projection)
            )

        open fun rewriteSelectProjectionValue(projection: SelectProjectionValue): SelectProjection =
            SelectProjectionValue(rewriteExprNode(projection.expr), rewriteMetas(projection))

        open fun rewriteSelectProjectionPivot(projection: SelectProjectionPivot): SelectProjection =
            SelectProjectionPivot(
                rewriteExprNode(projection.nameExpr),
                rewriteExprNode(projection.valueExpr),
                rewriteMetas(projection)
            )

        open fun rewriteSelectListItem(item: SelectListItem): SelectListItem =
            when (item) {
                is SelectListItemStar -> rewriteSelectListItemStar(item)
                is SelectListItemExpr -> rewriteSelectListItemExpr(item)
                is SelectListItemProjectAll -> rewriteSelectListItemProjectAll(item)
            }

        open fun rewriteSelectListItemProjectAll(item: SelectListItemProjectAll): SelectListItem =
            SelectListItemProjectAll(
                rewriteExprNode(item.expr)
            )

        open fun rewriteSelectListItemExpr(item: SelectListItemExpr): SelectListItem =
            SelectListItemExpr(
                rewriteExprNode(item.expr),
                item.asName?.let { rewriteSymbolicName(it) }
            )

        open fun rewriteSelectListItemStar(item: SelectListItemStar): SelectListItem =
            SelectListItemStar(rewriteMetas(item))

        open fun rewritePathComponent(pathComponent: PathComponent): PathComponent =
            when (pathComponent) {
                is PathComponentUnpivot -> rewritePathComponentUnpivot(pathComponent)
                is PathComponentWildcard -> rewritePathComponentWildcard(pathComponent)
                is PathComponentExpr -> rewritePathComponentExpr(pathComponent)
            }

        open fun rewritePathComponentUnpivot(pathComponent: PathComponentUnpivot): PathComponent =
            PathComponentUnpivot(rewriteMetas(pathComponent))

        open fun rewritePathComponentWildcard(pathComponent: PathComponentWildcard): PathComponent =
            PathComponentWildcard(rewriteMetas(pathComponent))

        open fun rewritePathComponentExpr(pathComponent: PathComponentExpr): PathComponent =
            PathComponentExpr(
                rewriteExprNode(pathComponent.expr),
                pathComponent.case,
                rewriteMetas(pathComponent)
            )

        open fun rewriteFromSource(fromSource: FromSource): FromSource =
            when (fromSource) {
                is FromSourceJoin -> rewriteFromSourceJoin(fromSource)
                is FromSourceLet -> rewriteFromSourceLet(fromSource)
            }

        open fun rewriteFromSourceLet(fromSourceLet: FromSourceLet): FromSourceLet =
            when (fromSourceLet) {
                is FromSourceExpr -> rewriteFromSourceExpr(fromSourceLet)
                is FromSourceUnpivot -> rewriteFromSourceUnpivot(fromSourceLet)
            }

        open fun rewriteLetVariables(variables: LetVariables) =
            LetVariables(
                variables.asName?.let { rewriteSymbolicName(it) },
                variables.atName?.let { rewriteSymbolicName(it) },
                variables.byName?.let { rewriteSymbolicName(it) }
            )

        open fun rewriteLetSource(letSource: LetSource) =
            LetSource(letSource.bindings.map { rewriteLetBinding(it) })

        open fun rewriteLetBinding(letBinding: LetBinding): LetBinding =
            LetBinding(rewriteExprNode(letBinding.expr), rewriteSymbolicName(letBinding.name))

        /**
         * This is called by the methods responsible for rewriting instances of the [FromSourceLet]
         * to rewrite their expression.  This exists to provide a place for derived rewriters to
         * affect state changes that apply *only* to the expression of these two node types.
         */
        open fun rewriteFromSourceValueExpr(expr: ExprNode): ExprNode =
            rewriteExprNode(expr)

        open fun rewriteFromSourceUnpivot(fromSource: FromSourceUnpivot): FromSourceLet =
            FromSourceUnpivot(
                rewriteFromSourceValueExpr(fromSource.expr),
                rewriteLetVariables(fromSource.variables),
                rewriteMetas(fromSource)
            )

        open fun rewriteFromSourceExpr(fromSource: FromSourceExpr): FromSourceLet =
            FromSourceExpr(
                rewriteFromSourceValueExpr(fromSource.expr),
                rewriteLetVariables(fromSource.variables)
            )

        open fun rewriteFromSourceJoin(fromSource: FromSourceJoin): FromSource =
            FromSourceJoin(
                fromSource.joinOp,
                rewriteFromSource(fromSource.leftRef),
                rewriteFromSource(fromSource.rightRef),
                rewriteExprNode(fromSource.condition),
                rewriteMetas(fromSource)
            )

        open fun rewriteGroupBy(groupBy: GroupBy): GroupBy =
            GroupBy(
                groupBy.grouping,
                groupBy.groupByItems.map { rewriteGroupByItem(it) },
                groupBy.groupName?.let { rewriteSymbolicName(it) }
            )

        open fun rewriteGroupByItem(item: GroupByItem): GroupByItem =
            GroupByItem(
                rewriteExprNode(item.expr),
                item.asName?.let { rewriteSymbolicName(it) }
            )

        open fun rewriteOrderBy(orderBy: OrderBy): OrderBy =
            OrderBy(
                orderBy.sortSpecItems.map { rewriteSortSpec(it) }
            )

        open fun rewriteSortSpec(sortSpec: SortSpec): SortSpec =
            SortSpec(
                rewriteExprNode(sortSpec.expr),
                sortSpec.orderingSpec,
                sortSpec.nullsSpec
            )

        open fun rewriteDataType(dataType: DataType) = dataType

        open fun rewriteSimpleCaseWhen(case: SimpleCaseWhen): SimpleCaseWhen =
            SimpleCaseWhen(
                rewriteExprNode(case.valueExpr),
                rewriteExprNode(case.thenExpr)
            )

        open fun rewriteSearchedCaseWhen(case: SearchedCaseWhen): SearchedCaseWhen =
            SearchedCaseWhen(
                rewriteExprNode(case.condition),
                rewriteExprNode(case.thenExpr)
            )

        open fun rewriteSymbolicName(symbolicName: SymbolicName): SymbolicName =
            SymbolicName(
                symbolicName.name,
                rewriteMetas(symbolicName)
            )

        open fun rewriteParameter(node: Parameter): Parameter =
            Parameter(node.position, rewriteMetas(node))

        open fun rewriteDataManipulation(node: DataManipulation): DataManipulation =
            innerRewriteDataManipulation(node)

        /**
         * Many subtypes of [AstRewriterBase] need to override [rewriteDataManipulation] to selectively apply a
         * different nested instance of themselves to [DataManipulation] nodes.  These subtypes can invoke this method
         * instead of [rewriteDataManipulation] to avoid infinite recursion.  They can also override this function if
         * they need to customize how the new [DataManipulation] node is instantiated.
         *
         * The traversal order is in the semantic order--that is:
         *
         * 1. `FROM` ([DataManipulation.from])
         * 2. `WHERE` ([DataManipulation.where])
         * 3. The DML operation ([DataManipulation.dmlOperation]]]
         * 4. The metas. ([DataManipulation.metas])
         */
        open fun innerRewriteDataManipulation(node: DataManipulation): DataManipulation {
            val from = node.from?.let { rewriteFromSource(it) }
            val where = node.where?.let { rewriteDataManipulationWhere(it) }
            val returning = node.returning?.let { rewriteReturningExpr(it) }
            val dmlOperations = rewriteDataManipulationOperations(node.dmlOperations)
            val metas = rewriteMetas(node)

            return DataManipulation(
                dmlOperations,
                from,
                where,
                returning,
                metas
            )
        }

        open fun rewriteDataManipulationWhere(node: ExprNode): ExprNode = rewriteExprNode(node)

        open fun rewriteReturningExpr(returningExpr: ReturningExpr): ReturningExpr =
            ReturningExpr(
                returningExpr.returningElems.map { rewriteReturningElem(it) }
            )

        open fun rewriteReturningElem(returningElem: ReturningElem): ReturningElem =
            ReturningElem(
                returningElem.returningMapping,
                returningElem.columnComponent
            )

        open fun rewriteDataManipulationOperations(node: DmlOpList): DmlOpList =
            DmlOpList(node.ops.map { rewriteDataManipulationOperation(it) })

        open fun rewriteDataManipulationOperation(node: DataManipulationOperation): DataManipulationOperation =
            when (node) {
                is InsertOp -> rewriteDataManipulationOperationInsertOp(node)
                is InsertValueOp -> rewriteDataManipulationOperationInsertValueOp(node)
                is AssignmentOp -> rewriteDataManipulationOperationAssignmentOp(node)
                is RemoveOp -> rewriteDataManipulationOperationRemoveOp(node)
                is DeleteOp -> rewriteDataManipulationOperationDeleteOp()
            }

        open fun rewriteDataManipulationOperationInsertOp(node: InsertOp): DataManipulationOperation =
            InsertOp(
                rewriteExprNode(node.lvalue),
                rewriteExprNode(node.values)
            )

        open fun rewriteDataManipulationOperationInsertValueOp(node: InsertValueOp): DataManipulationOperation =
            InsertValueOp(
                rewriteExprNode(node.lvalue),
                rewriteExprNode(node.value),
                node.position?.let { rewriteExprNode(it) },
                node.onConflict?.let { rewriteOnConflict(it) }
            )

        fun rewriteOnConflict(node: OnConflict): OnConflict {
            return OnConflict(rewriteExprNode(node.condition), node.conflictAction)
        }

        open fun rewriteDataManipulationOperationAssignmentOp(node: AssignmentOp): DataManipulationOperation =
            AssignmentOp(rewriteAssignment(node.assignment))

        open fun rewriteDataManipulationOperationRemoveOp(node: RemoveOp): DataManipulationOperation =
            RemoveOp(rewriteExprNode(node.lvalue))

        open fun rewriteDataManipulationOperationDeleteOp(): DataManipulationOperation = DeleteOp

        open fun rewriteAssignment(node: Assignment): Assignment =
            Assignment(
                rewriteExprNode(node.lvalue),
                rewriteExprNode(node.rvalue)
            )

        open fun rewriteCreateTable(node: CreateTable): CreateTable =
            CreateTable(node.tableName, rewriteMetas(node))

        open fun rewriteCreateIndex(node: CreateIndex): CreateIndex =
            CreateIndex(
                rewriteIdentifier(node.tableId),
                node.keys.map { rewriteExprNode(it) },
                rewriteMetas(node)
            )

        open fun rewriteDropTable(node: DropTable): DropTable =
            DropTable(rewriteIdentifier(node.tableId), rewriteMetas(node))

        open fun rewriteDropIndex(node: DropIndex): DropIndex =
            DropIndex(
                rewriteIdentifier(node.tableId),
                rewriteIdentifier(node.indexId),
                rewriteMetas(node)
            )

        open fun rewriteExec(node: Exec): Exec =
            Exec(
                rewriteSymbolicName(node.procedureName),
                node.args.map { rewriteExprNode(it) },
                rewriteMetas(node)
            )

        open fun rewriteDate(node: DateLiteral): DateLiteral =
            DateLiteral(
                node.year,
                node.month,
                node.day,
                rewriteMetas(node)
            )

        open fun rewriteTime(node: TimeLiteral): TimeLiteral =
            TimeLiteral(
                node.hour,
                node.minute,
                node.second,
                node.nano,
                node.precision,
                node.with_time_zone,
                node.tz_minutes,
                rewriteMetas(node)
            )

        open fun rewriteIdentifier(identifier: Identifier): Identifier =
            Identifier(
                identifier.id,
                identifier.case,
                rewriteMetas(identifier)
            )
    }
    