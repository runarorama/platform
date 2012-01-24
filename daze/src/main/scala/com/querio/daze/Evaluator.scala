/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.querio
package daze

import scalaz.{Identity => _, _}
import scalaz.effect._
import scalaz.iteratee._
import scalaz.syntax.traverse._
import scalaz.syntax.monad._
import IterateeT._

import com.reportgrid.analytics.Path
import com.reportgrid.yggdrasil._
import com.reportgrid.util._

trait Evaluator extends DAG with CrossOrdering with OperationsAPI {
  import Function._
  
  import instructions._
  import dag._
  
  def eval[X](graph: DepGraph): DatasetEnum[X, SEvent, IO] = {
    def loop(graph: DepGraph, roots: List[DatasetEnum[X, SEvent, IO]]): DatasetEnum[X, SEvent, IO] = graph match {
      case SplitRoot(_, depth) => roots(depth)
      
      case Root(_, instr) => {
        val sev = instr match {
          case PushString(str) => SString(str)
          case PushNum(str) => SDecimal(BigDecimal(str))
          case PushTrue => SBoolean(true)
          case PushFalse => SBoolean(false)
          case PushObject => SObject(Map())
          case PushArray => SArray(Vector())
        }
        
        ops.point((Vector(), sev))
      }
      
      case dag.New(_, parent) => loop(parent, roots)
      
      case dag.LoadLocal(_, _, parent, _) => {
        implicit val order = identitiesOrder(parent.provenance.length)
        
        ops.flatMap(loop(parent, roots)) {
          case (_, SString(str)) => query.fullProjection(Path(str))
          case _ => ops.empty[X, SEvent, IO]
        }
      }
      
      case Operate(_, Comp, parent) => {
        val enum = loop(parent, roots)
        
        ops.collect(enum) {
          case (id, SBoolean(b)) => (id, SBoolean(!b))
        }
      }
      
      case Operate(_, Neg, parent) => {
        val enum = loop(parent, roots)
        
        ops.collect(enum) {
          case (id, SDecimal(d)) => (id, SDecimal(-d))
        }
      }
      
      case Operate(_, WrapArray, parent) => {
        val enum = loop(parent, roots)
        
        ops.map(enum) {
          case (id, sv) => (id, SArray(Vector(sv)))
        }
      }
      
      case dag.Reduce(_, red, parent) => {
        val enum = loop(parent, roots).enum

        val reducedEnumP: EnumeratorP[X, SEvent, IO] = new EnumeratorP[X, SEvent, IO] {
          def apply[F[_[_], _]: MonadTrans]: EnumeratorT[X, SEvent, ({type λ[α] = F[IO, α] })#λ] = {
            type FIO[α] = F[IO, α]
            implicit val FMonad: Monad[FIO] = MonadTrans[F].apply[IO]

            new EnumeratorT[X, SEvent, FIO] {
              def apply[A] = (step: StepT[X, SEvent, FIO, A]) => 
                for {
                  opt <- reductionIter[X, F](red) &= enum[F]
                  a   <- step.pointI &= (opt.map(sv => EnumeratorT.enumOne[X, SEvent, FIO](Vector(), sv)).getOrElse(Monoid[EnumeratorT[X, SEvent, FIO]].zero))
                } yield a
            }
          }
        }

        DatasetEnum[X, SEvent, IO](reducedEnumP)
      }
      
      case dag.Split(_, parent, child) => {
        implicit val order = identitiesOrder(child.provenance.length)
        
        val splitEnum = loop(parent, roots)
        
        ops.flatMap(splitEnum) {
          case (_, sv) => loop(child, ops.point[X, SEvent, IO]((Vector(), sv)) :: roots)
        }
      }
      
      case Join(_, instr @ (VUnion | VIntersect), left, right) => {
        implicit val sortOfValueOrder = ValuesOrder
        
        val leftEnum = ops.sort(loop(left, roots))
        val rightEnum = ops.sort(loop(right, roots))
        
        // TODO we're relying on the fact that we *don't* need to preserve sane identities!
        // Throwing away the right one is probably broken unless your ordering respects both
        // objects and identities
        ops.collect(ops.cogroup(leftEnum, rightEnum)) {
          case Left3(sev)  if instr == VUnion => sev
          case Middle3((sev1, _))             => sev1
          case Right3(sev) if instr == VUnion => sev
        }
      }
      
      // TODO IUnion and IIntersect
      
      case Join(_, instr, left, right) => {
        implicit lazy val order = identitiesOrder(sharedPrefixLength(left, right))
        
        val leftEnum = loop(left, roots)
        val rightEnum = loop(right, roots)
        
        val (pairs, op, distinct) = instr match {
          case Map2Match(op) => (ops.join(leftEnum, rightEnum), op, true)
          case Map2Cross(op) => (ops.crossLeft(leftEnum, rightEnum), op, false)
          case Map2CrossLeft(op) => (ops.crossLeft(leftEnum, rightEnum), op, false)
          case Map2CrossRight(op) => (ops.crossRight(leftEnum, rightEnum), op, false)
        }
        
        ops.collect(pairs) {
          unlift {
            case ((ids1, sv1), (ids2, sv2)) => {
              val ids = if (distinct)
                (ids1 ++ ids2).distinct
              else
                ids1 ++ ids2
              
              binaryOp(op)(sv1, sv2) map { sv => (ids, sv) }
            }
          }
        }
      }
      
      case Filter(_, cross, _, target, boolean) => {
        implicit lazy val order = identitiesOrder(sharedPrefixLength(target, boolean))
        
        val targetEnum = loop(target, roots)
        val booleanEnum = loop(boolean, roots)
        
        val (pairs, distinct) = cross match {
          case None => (ops.join(targetEnum, booleanEnum), true)
          case Some(CrossNeutral) => (ops.crossLeft(targetEnum, booleanEnum), false)
          case Some(CrossLeft) => (ops.crossLeft(targetEnum, booleanEnum), false)
          case Some(CrossRight) => (ops.crossRight(targetEnum, booleanEnum), false)
        }
        
        ops.collect(pairs) {
          case ((ids1, sv), (ids2, SBoolean(true))) => {
            val ids = if (distinct)
              (ids1 ++ ids2).distinct
            else
              ids1 ++ ids2
            
            (ids, sv)
          }
        }
      }
      
      case Sort(parent, indexes) => {
        implicit val order: Order[SEvent] = new Order[SEvent] {
          def order(e1: SEvent, e2: SEvent): Ordering = {
            val (ids1, _) = e1
            val (ids2, _) = e2
            
            val left = indexes map ids1
            val right = indexes map ids2
            
            (left zip right).foldLeft[Ordering](Ordering.EQ) {
              case (Ordering.EQ, (i1, i2)) => Ordering.fromInt((i1 - i2) toInt)
              case (acc, _) => acc
            }
          }
        }
        
        ops.map(ops.sort(loop(parent, roots))) {
          case (ids, sv) => {
            val (first, second) = ids.zipWithIndex partition {
              case (_, i) => indexes contains i
            }
        
            val prefix = first sortWith {
              case ((_, i1), (_, i2)) => indexes.indexOf(i1) < indexes.indexOf(i2)
            }
            
            val (back, _) = (prefix ++ second).unzip
            (back, sv)
          }
        }
      }
    }
    
    loop(orderCrosses(graph), Nil)
  }

  private def unlift[A, B](f: A => Option[B]): PartialFunction[A, B] = new PartialFunction[A, B] {
    def apply(a: A) = f(a).get
    def isDefinedAt(a: A) = f(a).isDefined
  }
  
  private def reductionIter[X, F[_[_], _]: MonadTrans](red: Reduction): IterateeT[X, SEvent, ({ type λ[α] = F[IO, α] })#λ, Option[SValue]] =
    sys.error("no reductions implemented yet...")
  
  private def binaryOp(op: BinaryOperation): (SValue, SValue) => Option[SValue] = {
    import Function._
    
    def add(left: BigDecimal, right: BigDecimal): Option[BigDecimal] = Some(left + right)
    def sub(left: BigDecimal, right: BigDecimal): Option[BigDecimal] = Some(left - right)
    def mul(left: BigDecimal, right: BigDecimal): Option[BigDecimal] = Some(left * right)
    
    def div(left: BigDecimal, right: BigDecimal): Option[BigDecimal] = {
      if (right == 0)
        None
      else
        Some(left / right)
    }
    
    def lt(left: BigDecimal, right: BigDecimal): Option[Boolean] = Some(left < right)
    def lteq(left: BigDecimal, right: BigDecimal): Option[Boolean] = Some(left <= right)
    def gt(left: BigDecimal, right: BigDecimal): Option[Boolean] = Some(left > right)
    def gteq(left: BigDecimal, right: BigDecimal): Option[Boolean] = Some(left >= right)
    
    def eq(left: SValue, right: SValue): Option[Boolean] = Some(left == right)
    def neq(left: SValue, right: SValue): Option[Boolean] = Some(left != right)
    
    def and(left: Boolean, right: Boolean): Option[Boolean] = Some(left && right)
    def or(left: Boolean, right: Boolean): Option[Boolean] = Some(left || right)
    
    def joinObject(left: Map[String, SValue], right: Map[String, SValue]) = Some(left ++ right)
    def joinArray(left: Vector[SValue], right: Vector[SValue]) = Some(left ++ right)
    
    def coerceNumerics(pair: (SValue, SValue)): Option[(BigDecimal, BigDecimal)] = pair match {
      case (SDecimal(left), SDecimal(right)) => Some((left, right))
      case _ => None
    }
    
    def coerceBooleans(pair: (SValue, SValue)): Option[(Boolean, Boolean)] = pair match {
      case (SBoolean(left), SBoolean(right)) => Some((left, right))
      case _ => None
    }
    
    def coerceObjects(pair: (SValue, SValue)): Option[(Map[String, SValue], Map[String, SValue])] = pair match {
      case (SObject(left), SObject(right)) => Some((left, right))
      case _ => None
    }
    
    def coerceArrays(pair: (SValue, SValue)): Option[(Vector[SValue], Vector[SValue])] = pair match {
      case (SArray(left), SArray(right)) => Some((left, right))
      case _ => None
    }
    
    op match {
      case Add => untupled((coerceNumerics _) andThen { _ flatMap (add _).tupled map SDecimal })
      case Sub => untupled((coerceNumerics _) andThen { _ flatMap (sub _).tupled map SDecimal })
      case Mul => untupled((coerceNumerics _) andThen { _ flatMap (mul _).tupled map SDecimal })
      case Div => untupled((coerceNumerics _) andThen { _ flatMap (div _).tupled map SDecimal })
      
      case Lt => untupled((coerceNumerics _) andThen { _ flatMap (lt _).tupled map SBoolean })
      case LtEq => untupled((coerceNumerics _) andThen { _ flatMap (lteq _).tupled map SBoolean })
      case Gt => untupled((coerceNumerics _) andThen { _ flatMap (gt _).tupled map SBoolean })
      case GtEq => untupled((coerceNumerics _) andThen { _ flatMap (gteq _).tupled map SBoolean })
      
      case Eq => untupled((eq _).tupled andThen { _ map SBoolean })
      case NotEq => untupled((neq _).tupled andThen { _ map SBoolean })
      
      case And => untupled((coerceBooleans _) andThen { _ flatMap (and _).tupled map SBoolean })
      case Or => untupled((coerceBooleans _) andThen { _ flatMap (or _).tupled map SBoolean })
      
      case WrapObject => {
        case (SString(key), value) => Some(SObject(Map(key -> value)))
        case _ => None
      }
      
      case JoinObject => untupled((coerceObjects _) andThen { _ flatMap (joinObject _).tupled map SObject })
      case JoinArray => untupled((coerceArrays _) andThen { _ flatMap (joinArray _).tupled map SArray })
      
      case ArraySwap => {
        case (SDecimal(index), SArray(arr)) if index.isValidInt => {
          val i = index.toInt - 1
          if (i < 0 || i >= arr.length) {
            None
          } else {
            val (left, right) = arr splitAt i
            Some(SArray(left.init ++ Vector(right.head, left.last) ++ left.tail))
          }
        }
        
        case _ => None
      }
      
      case DerefObject => {
        case (SString(key), SObject(obj)) => obj get key
        case _ => None
      }
      
      case DerefArray => {
        case (SDecimal(index), SArray(arr)) if index.isValidInt =>
          arr.lift(index.toInt)
        
        case _ => None
      }
    }
  }
  
  private def sharedPrefixLength(left: DepGraph, right: DepGraph): Int =
    left.provenance zip right.provenance takeWhile { case (a, b) => a == b } length

  private def identitiesOrder(prefixLength: Int): Order[SEvent] = new Order[SEvent] {
    def order(e1: SEvent, e2: SEvent) = {
      val (ids1, _) = e1
      val (ids2, _) = e2
      
      (ids1 zip ids2 take prefixLength).foldLeft[Ordering](Ordering.EQ) {
        case (Ordering.EQ, (i1, i2)) => Ordering.fromInt((i1 - i2) toInt)
        case (acc, _) => acc
      }
    }
  }
  
  /**
   * Implements a sort on event values that is ''stable'', but not coherant.
   * In other words, this is sufficient for union, intersect, match, etc, but is
   * not actually going to give you sane answers if you 
   */
  private object ValuesOrder extends Order[SEvent] {
    def order(e1: SEvent, e2: SEvent) = {
      val (_, sv1) = e1
      val (_, sv2) = e2
      
      orderValues(sv1, sv2)
    }
    
    private def orderValues(sv1: SValue, sv2: SValue): Ordering = {
      val to1 = typeOrdinal(sv1)
      val to2 = typeOrdinal(sv2)
      
      if (to1 == to2) {
        (sv1, sv2) match {
          case (SBoolean(b1), SBoolean(b2)) => Ordering.fromInt(boolOrdinal(b1) - boolOrdinal(b2))
          
          case (SLong(l1), SLong(l2)) => {
            if (l1 < l2)
              Ordering.LT
            else if (l1 == l2)
              Ordering.EQ
            else
              Ordering.GT
          }
          
          case (SDouble(d1), SDouble(d2)) => {
            if (d1 < d2)
              Ordering.LT
            else if (d1 == d2)
              Ordering.EQ
            else
              Ordering.GT
          }
          
          case (SDecimal(d1), SDecimal(d2)) => {
            if (d1 < d2)
              Ordering.LT
            else if (d1 == d2)
              Ordering.EQ
            else
              Ordering.GT
          }
          
          case (SString(str1), SString(str2)) => {
            if (str1 < str2)
              Ordering.LT
            else if (str1 == str2)
              Ordering.EQ
            else
              Ordering.GT
          }
          
          case (SArray(arr1), SArray(arr2)) => {
            if (arr1.length < arr2.length) {
              Ordering.LT
            } else if (arr1.length == arr2.length) {
              val orderings = arr1.view zip arr2.view map {
                case (sv1, sv2) => orderValues(sv1, sv2)
              }
              
              (orderings dropWhile (Ordering.EQ ==) headOption) getOrElse Ordering.EQ
            } else {
              Ordering.GT
            }
          }
          
          case (SObject(obj1), SObject(obj2)) => {
            if (obj1.size < obj2.size) {
              Ordering.LT
            } else if (obj1.size == obj2.size) {
              val pairs1 = obj1.toSeq sortWith { case ((k1, _), (k2, _)) => k1 < k2 }
              val pairs2 = obj2.toSeq sortWith { case ((k1, _), (k2, _)) => k1 < k2 }
              
              val comparisons = pairs1 zip pairs2 map {
                case ((k1, _), (k2, _)) if k1 < k2 => Ordering.LT
                case ((k1, _), (k2, _)) if k1 == k2 => Ordering.EQ
                case ((k1, _), (k2, _)) if k1 > k2 => Ordering.GT
              }
              
              val netKeys = (comparisons dropWhile (Ordering.EQ ==) headOption) getOrElse Ordering.EQ
              
              if (netKeys == Ordering.EQ) {
                val comparisons = pairs1 zip pairs2 map {
                  case ((_, v1), (_, v2)) => orderValues(v1, v2)
                }
                
                (comparisons dropWhile (Ordering.EQ ==) headOption) getOrElse Ordering.EQ
              } else {
                netKeys
              }
            } else {
              Ordering.GT
            }
          }
        }
      } else {
        Ordering.fromInt(to1 - to2)
      }
    }
    
    private def typeOrdinal(sv: SValue) = sv match {
      case SBoolean(_) => 0
      case SLong(_) => 1
      case SDouble(_) => 2
      case SDecimal(_) => 3
      case SString(_) => 4
      case SArray(_) => 5
      case SObject(_) => 6
    }
    
    private def boolOrdinal(b: Boolean) = if (b) 1 else 0
  }
}