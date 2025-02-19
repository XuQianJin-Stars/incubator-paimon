/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.paimon.spark.catalyst.analysis.expressions

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{And, Attribute, Cast, Expression, GetStructField, Literal, PredicateHelper}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DataType, NullType}

/** An expression helper. */
trait ExpressionHelper extends PredicateHelper {

  import ExpressionHelper._

  protected def resolveExpression(
      spark: SparkSession)(expr: Expression, plan: LogicalPlan): Expression = {
    if (expr.resolved) {
      expr
    } else {
      val newPlan = FakeLogicalPlan(Seq(expr), plan.children)
      spark.sessionState.analyzer.execute(newPlan) match {
        case FakeLogicalPlan(resolvedExpr, _) =>
          resolvedExpr.head
        case _ =>
          throw new RuntimeException(s"Could not resolve expression $expr in plan: $plan")
      }
    }
  }

  protected def resolveExpressions(
      spark: SparkSession)(exprs: Seq[Expression], plan: LogicalPlan): Seq[Expression] = {
    val newPlan = FakeLogicalPlan(exprs, plan.children)
    spark.sessionState.analyzer.execute(newPlan) match {
      case FakeLogicalPlan(resolvedExpr, _) =>
        resolvedExpr
      case _ =>
        throw new RuntimeException(s"Could not resolve expressions $exprs in plan: $plan")
    }
  }

  /**
   * Get the parts of the expression that are only relevant to the plan, and then compose the new
   * expression.
   */
  protected def getExpressionOnlyRelated(
      expression: Expression,
      plan: LogicalPlan): Option[Expression] = {
    val expressions = splitConjunctivePredicates(expression).filter {
      expression => canEvaluate(expression, plan) && canEvaluateWithinJoin(expression)
    }
    expressions.reduceOption(And)
  }

  protected def castIfNeeded(fromExpression: Expression, toDataType: DataType): Expression = {
    fromExpression match {
      case Literal(null, NullType) => Literal(null, toDataType)
      case _ =>
        val fromDataType = fromExpression.dataType
        if (!Cast.canCast(fromDataType, toDataType)) {
          throw new RuntimeException(s"Can't cast from $fromDataType to $toDataType.")
        }
        if (DataType.equalsIgnoreCaseAndNullability(fromDataType, toDataType)) {
          fromExpression
        } else {
          Cast(fromExpression, toDataType, Option(SQLConf.get.sessionLocalTimeZone))
        }
    }
  }

  protected def toRefSeq(expr: Expression): Seq[String] = expr match {
    case attr: Attribute =>
      Seq(attr.name)
    case GetStructField(child, _, Some(name)) =>
      toRefSeq(child) :+ name
    case other =>
      throw new UnsupportedOperationException(
        s"Unsupported update expression: $other, only support update with PrimitiveType and StructType.")
  }
}

object ExpressionHelper {

  case class FakeLogicalPlan(exprs: Seq[Expression], children: Seq[LogicalPlan])
    extends LogicalPlan {
    override def output: Seq[Attribute] = Nil

    override protected def withNewChildrenInternal(
        newChildren: IndexedSeq[LogicalPlan]): FakeLogicalPlan = copy(children = newChildren)
  }
}
