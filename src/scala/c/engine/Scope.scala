package scala.c.engine

import c.engine._
import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ListBuffer, Stack}

case class NodePath(node: IASTNode, var direction: Direction)

class FunctionScope(theStaticVars: List[Variable], parent: Scope, val function: IFunctionType, theState: State)
  extends Scope(theStaticVars, parent, theState) {
  val labels = new ListBuffer[(String, Stack[NodePath])]()
}

trait ContinuableScope
trait BreakableScope

class LoopScope(theStaticVars: List[Variable], parent: Scope, theState: State)
  extends Scope(theStaticVars, parent, theState) with ContinuableScope with BreakableScope {
}

abstract class Scope(staticVars: List[Variable], parent: Scope, state: State) {
  private var varMap = new ListBuffer[Variable]()
  val pathStack = new Stack[NodePath]()
  val stack = new Stack[ValueType]()
  var startingStackAddr = 0

  reset

  def resolveId(name: IASTName): Option[Variable] = {
    varMap.find{_.name.getRawSignature == name.getRawSignature}
      .orElse(if (parent != null) parent.resolveId(name) else None)
      .orElse(Some(state.functionPointers(name.getRawSignature)))
  }

  def reset = {
    varMap.clear
    varMap ++= staticVars.toSet.toList
    pathStack.clear
    stack.clear
    startingStackAddr = state.stackInsertIndex
  }

  def addArrayVariable(name: IASTName, theType: IArrayType, dimensions: Seq[Int]): ArrayVariable = {

    if (!staticVars.exists{_.name == name.getRawSignature}) {
      val newVar = new ArrayVariable(name, state, theType, dimensions)
      varMap = varMap.filter { theVar => theVar.name.getRawSignature != name.getRawSignature } :+ newVar
      newVar
    } else {
      staticVars.find{_.name.getRawSignature == name.getRawSignature}.get.asInstanceOf[ArrayVariable]
    }
  }

  def addVariable(name: IASTName, theType: IType): Variable = {

    if (!staticVars.exists{_.name.getRawSignature == name.getRawSignature}) {
      val newVar = new Variable(name, state, theType)
      varMap = varMap.filter { theVar => theVar.name.getRawSignature != name.getRawSignature } :+ newVar
      newVar
    } else {
      staticVars.find{_.name.getRawSignature == name.getRawSignature}.get
    }
  }
}