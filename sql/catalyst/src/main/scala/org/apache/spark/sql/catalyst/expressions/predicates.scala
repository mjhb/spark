/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.analysis.UnresolvedException
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.types.{DataType, BinaryType, BooleanType, AtomicType}

object InterpretedPredicate {
  def create(expression: Expression, inputSchema: Seq[Attribute]): (Row => Boolean) =
    create(BindReferences.bindReference(expression, inputSchema))

  def create(expression: Expression): (Row => Boolean) = {
    (r: Row) => expression.eval(r).asInstanceOf[Boolean]
  }
}

trait Predicate extends Expression {
  self: Product =>

  override def dataType: DataType = BooleanType

  type EvaluatedType = Any
}

trait PredicateHelper {
  protected def splitConjunctivePredicates(condition: Expression): Seq[Expression] = {
    condition match {
      case And(cond1, cond2) =>
        splitConjunctivePredicates(cond1) ++ splitConjunctivePredicates(cond2)
      case other => other :: Nil
    }
  }

  protected def splitDisjunctivePredicates(condition: Expression): Seq[Expression] = {
    condition match {
      case Or(cond1, cond2) =>
        splitDisjunctivePredicates(cond1) ++ splitDisjunctivePredicates(cond2)
      case other => other :: Nil
    }
  }

  /**
   * Returns true if `expr` can be evaluated using only the output of `plan`.  This method
   * can be used to determine when is is acceptable to move expression evaluation within a query
   * plan.
   *
   * For example consider a join between two relations R(a, b) and S(c, d).
   *
   * `canEvaluate(EqualTo(a,b), R)` returns `true` where as `canEvaluate(EqualTo(a,c), R)` returns
   * `false`.
   */
  protected def canEvaluate(expr: Expression, plan: LogicalPlan): Boolean =
    expr.references.subsetOf(plan.outputSet)
}


case class Not(child: Expression) extends UnaryExpression with Predicate with ExpectsInputTypes {
  override def foldable: Boolean = child.foldable
  override def nullable: Boolean = child.nullable
  override def toString: String = s"NOT $child"

  override def expectedChildTypes: Seq[DataType] = Seq(BooleanType)

  override def eval(input: Row): Any = {
    child.eval(input) match {
      case null => null
      case b: Boolean => !b
    }
  }
}

/**
 * Evaluates to `true` if `list` contains `value`.
 */
case class In(value: Expression, list: Seq[Expression]) extends Predicate {
  override def children: Seq[Expression] = value +: list

  override def nullable: Boolean = true // TODO: Figure out correct nullability semantics of IN.
  override def toString: String = s"$value IN ${list.mkString("(", ",", ")")}"

  override def eval(input: Row): Any = {
    val evaluatedValue = value.eval(input)
    list.exists(e => e.eval(input) == evaluatedValue)
  }
}

/**
 * Optimized version of In clause, when all filter values of In clause are
 * static.
 */
case class InSet(value: Expression, hset: Set[Any])
  extends Predicate {

  override def children: Seq[Expression] = value :: Nil

  override def nullable: Boolean = true // TODO: Figure out correct nullability semantics of IN.
  override def toString: String = s"$value INSET ${hset.mkString("(", ",", ")")}"

  override def eval(input: Row): Any = {
    hset.contains(value.eval(input))
  }
}

case class And(left: Expression, right: Expression)
  extends BinaryExpression with Predicate with ExpectsInputTypes {

  override def expectedChildTypes: Seq[DataType] = Seq(BooleanType, BooleanType)

  override def symbol: String = "&&"

  override def eval(input: Row): Any = {
    val l = left.eval(input)
    if (l == false) {
       false
    } else {
      val r = right.eval(input)
      if (r == false) {
        false
      } else {
        if (l != null && r != null) {
          true
        } else {
          null
        }
      }
    }
  }
}

case class Or(left: Expression, right: Expression)
  extends BinaryExpression with Predicate with ExpectsInputTypes {

  override def expectedChildTypes: Seq[DataType] = Seq(BooleanType, BooleanType)

  override def symbol: String = "||"

  override def eval(input: Row): Any = {
    val l = left.eval(input)
    if (l == true) {
      true
    } else {
      val r = right.eval(input)
      if (r == true) {
        true
      } else {
        if (l != null && r != null) {
          false
        } else {
          null
        }
      }
    }
  }
}

abstract class BinaryComparison extends BinaryExpression with Predicate {
  self: Product =>
}

case class EqualTo(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "="

  override def eval(input: Row): Any = {
    val l = left.eval(input)
    if (l == null) {
      null
    } else {
      val r = right.eval(input)
      if (r == null) null
      else if (left.dataType != BinaryType) l == r
      else java.util.Arrays.equals(l.asInstanceOf[Array[Byte]], r.asInstanceOf[Array[Byte]])
    }
  }
}

case class EqualNullSafe(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "<=>"

  override def nullable: Boolean = false

  override def eval(input: Row): Any = {
    val l = left.eval(input)
    val r = right.eval(input)
    if (l == null && r == null) {
      true
    } else if (l == null || r == null) {
      false
    } else {
      l == r
    }
  }
}

case class LessThan(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "<"

  lazy val ordering: Ordering[Any] = {
    if (left.dataType != right.dataType) {
      throw new TreeNodeException(this,
        s"Types do not match ${left.dataType} != ${right.dataType}")
    }
    left.dataType match {
      case i: AtomicType => i.ordering.asInstanceOf[Ordering[Any]]
      case other => sys.error(s"Type $other does not support ordered operations")
    }
  }

  override def eval(input: Row): Any = {
    val evalE1 = left.eval(input)
    if (evalE1 == null) {
      null
    } else {
      val evalE2 = right.eval(input)
      if (evalE2 == null) {
        null
      } else {
        ordering.lt(evalE1, evalE2)
      }
    }
  }
}

case class LessThanOrEqual(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "<="

  lazy val ordering: Ordering[Any] = {
    if (left.dataType != right.dataType) {
      throw new TreeNodeException(this,
        s"Types do not match ${left.dataType} != ${right.dataType}")
    }
    left.dataType match {
      case i: AtomicType => i.ordering.asInstanceOf[Ordering[Any]]
      case other => sys.error(s"Type $other does not support ordered operations")
    }
  }

  override def eval(input: Row): Any = {
    val evalE1 = left.eval(input)
    if (evalE1 == null) {
      null
    } else {
      val evalE2 = right.eval(input)
      if (evalE2 == null) {
        null
      } else {
        ordering.lteq(evalE1, evalE2)
      }
    }
  }
}

case class GreaterThan(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = ">"

  lazy val ordering: Ordering[Any] = {
    if (left.dataType != right.dataType) {
      throw new TreeNodeException(this,
        s"Types do not match ${left.dataType} != ${right.dataType}")
    }
    left.dataType match {
      case i: AtomicType => i.ordering.asInstanceOf[Ordering[Any]]
      case other => sys.error(s"Type $other does not support ordered operations")
    }
  }

  override def eval(input: Row): Any = {
    val evalE1 = left.eval(input)
    if(evalE1 == null) {
      null
    } else {
      val evalE2 = right.eval(input)
      if (evalE2 == null) {
        null
      } else {
        ordering.gt(evalE1, evalE2)
      }
    }
  }
}

case class GreaterThanOrEqual(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = ">="

  lazy val ordering: Ordering[Any] = {
    if (left.dataType != right.dataType) {
      throw new TreeNodeException(this,
        s"Types do not match ${left.dataType} != ${right.dataType}")
    }
    left.dataType match {
      case i: AtomicType => i.ordering.asInstanceOf[Ordering[Any]]
      case other => sys.error(s"Type $other does not support ordered operations")
    }
  }

  override def eval(input: Row): Any = {
    val evalE1 = left.eval(input)
    if (evalE1 == null) {
      null
    } else {
      val evalE2 = right.eval(input)
      if (evalE2 == null) {
        null
      } else {
        ordering.gteq(evalE1, evalE2)
      }
    }
  }
}

case class If(predicate: Expression, trueValue: Expression, falseValue: Expression)
  extends Expression {

  override def children: Seq[Expression] = predicate :: trueValue :: falseValue :: Nil
  override def nullable: Boolean = trueValue.nullable || falseValue.nullable

  override lazy val resolved = childrenResolved && trueValue.dataType == falseValue.dataType
  override def dataType: DataType = {
    if (!resolved) {
      throw new UnresolvedException(
        this,
        s"Can not resolve due to differing types ${trueValue.dataType}, ${falseValue.dataType}")
    }
    trueValue.dataType
  }

  type EvaluatedType = Any

  override def eval(input: Row): Any = {
    if (true == predicate.eval(input)) {
      trueValue.eval(input)
    } else {
      falseValue.eval(input)
    }
  }

  override def toString: String = s"if ($predicate) $trueValue else $falseValue"
}

// scalastyle:off
/**
 * Case statements of the form "CASE WHEN a THEN b [WHEN c THEN d]* [ELSE e] END".
 * Refer to this link for the corresponding semantics:
 * https://cwiki.apache.org/confluence/display/Hive/LanguageManual+UDF#LanguageManualUDF-ConditionalFunctions
 *
 * The other form of case statements "CASE a WHEN b THEN c [WHEN d THEN e]* [ELSE f] END" gets
 * translated to this form at parsing time.  Namely, such a statement gets translated to
 * "CASE WHEN a=b THEN c [WHEN a=d THEN e]* [ELSE f] END".
 *
 * Note that `branches` are considered in consecutive pairs (cond, val), and the optional last
 * element is the value for the default catch-all case (if provided). Hence, `branches` consists of
 * at least two elements, and can have an odd or even length.
 */
// scalastyle:on
case class CaseWhen(branches: Seq[Expression]) extends Expression {
  type EvaluatedType = Any

  override def children: Seq[Expression] = branches

  override def dataType: DataType = {
    if (!resolved) {
      throw new UnresolvedException(this, "cannot resolve due to differing types in some branches")
    }
    branches(1).dataType
  }

  @transient private[this] lazy val branchesArr = branches.toArray
  @transient private[this] lazy val predicates =
    branches.sliding(2, 2).collect { case Seq(cond, _) => cond }.toSeq
  @transient private[this] lazy val values =
    branches.sliding(2, 2).collect { case Seq(_, value) => value }.toSeq
  @transient private[this] lazy val elseValue =
    if (branches.length % 2 == 0) None else Option(branches.last)

  override def nullable: Boolean = {
    // If no value is nullable and no elseValue is provided, the whole statement defaults to null.
    values.exists(_.nullable) || (elseValue.map(_.nullable).getOrElse(true))
  }

  override lazy val resolved: Boolean = {
    if (!childrenResolved) {
      false
    } else {
      val allCondBooleans = predicates.forall(_.dataType == BooleanType)
      // both then and else val should be considered.
      val dataTypesEqual = (values ++ elseValue).map(_.dataType).distinct.size <= 1
      allCondBooleans && dataTypesEqual
    }
  }

  /** Written in imperative fashion for performance considerations. */
  override def eval(input: Row): Any = {
    val len = branchesArr.length
    var i = 0
    // If all branches fail and an elseVal is not provided, the whole statement
    // defaults to null, according to Hive's semantics.
    var res: Any = null
    while (i < len - 1) {
      if (branchesArr(i).eval(input) == true) {
        res = branchesArr(i + 1).eval(input)
        return res
      }
      i += 2
    }
    if (i == len - 1) {
      res = branchesArr(i).eval(input)
    }
    res
  }

  override def toString: String = {
    "CASE" + branches.sliding(2, 2).map {
      case Seq(cond, value) => s" WHEN $cond THEN $value"
      case Seq(elseValue) => s" ELSE $elseValue"
    }.mkString
  }
}
