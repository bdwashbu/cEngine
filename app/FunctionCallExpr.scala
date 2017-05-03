package app.astViewer

import org.eclipse.cdt.core.dom.ast.IASTFunctionCallExpression

import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ListBuffer, Stack}
import scala.util.control.Exception.allCatch
import java.util.Formatter;
import java.util.Locale;
import Functions._
import org.eclipse.cdt.internal.core.dom.parser.c.CBasicType
import org.eclipse.cdt.internal.core.dom.parser.c.CStructure
import scala.collection.immutable.HashMap
import org.eclipse.cdt.internal.core.dom.parser.c.CPointerType

object FunctionCallExpr {
  
  val varArgs = new ListBuffer[Any]()
  
  

  
  def parse(call: IASTFunctionCallExpression, direction: Direction)(implicit state: State): Seq[IASTNode] = {
    if (direction == Exiting) {
        val name = state.stack.pop match {
          case AddressInfo(addr, theType) => state.getFunctionByIndex(state.readPtrVal(addr).value.asInstanceOf[Int]).name
        }

        val results = 
          if (name == "printf") {
          
          // do this up here so string allocation doesnt clobber arg stack
          val results = call.getArguments.map{call => 
            state.stack.pop match {
              case Variable(theInfo: Variable) => 
                val info = theInfo
                if (TypeHelper.isPointer(info.theType) && TypeHelper.getPointedType(info.theType).isInstanceOf[IBasicType] &&
                    TypeHelper.getPointedType(info.theType).asInstanceOf[IBasicType].getKind == IBasicType.Kind.eChar) {
                  ValueInfo(Address(info.value.value.asInstanceOf[Int]), info.theType)
                } else {
                  info.value
                }
              case x => Utils.allocateString(x, false)
            }
            
          }

          results
        } else {
          
          val rawResults = call.getArguments.map{x => state.stack.pop}
          
          // do this up here so string allocation doesnt clobber arg stack
          rawResults.map{x =>
            Utils.allocateString(x, false)
          }
        }

        state.callTheFunction(name, call, results)

      } else {
        call.getArguments.reverse ++ Seq(call.getFunctionNameExpression)
      }
  }
}