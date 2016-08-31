package app.astViewer

import org.eclipse.cdt.core.dom.ast._

import scala.collection.mutable.{ListBuffer, Stack}
import scala.util.control.Exception.allCatch
import java.util.Formatter;
import java.util.Locale;
import java.nio.ByteBuffer
import java.nio.ByteOrder

class State {
  val stack = new Stack[Any]()
  val rawDataStack = new VarStack
  val executionContext = new Stack[FunctionExecutionContext]()
  val globals = new ListBuffer[Variable]()  
  var vars: FunctionExecutionContext = null
  val functionMap = scala.collection.mutable.Map[String, IASTNode]()
  val stdout = new ListBuffer[String]()
  var currentType: IASTDeclSpecifier = null
  
  // flags
  var isBreaking = false;
  var isPreprocessing = true
  
  var parsingAssignmentDest = false
  
  def callFunction(call: IASTFunctionCallExpression) = {
    executionContext.push(vars)
    vars = new FunctionExecutionContext(globals)
    
    val name = call.getFunctionNameExpression match {
      case x: IASTIdExpression => x.getName.getRawSignature
      case _ => "Error"
    }
    
    Seq(functionMap(name))
  }
  
  def clearVisited(parent: IASTNode) {
    vars.visited -= parent
    parent.getChildren.foreach { node =>
      clearVisited(node)
    }
  }
  
  class VarStack {
    private val data = ByteBuffer.allocate(10240);
    data.order(ByteOrder.LITTLE_ENDIAN)
    
    var insertIndex = 0
    
    case class MemRange(start: Int, end: Int, typeName: String)
  
    private val records = new ListBuffer[MemRange]()
    
    def getType(address: Address): String = {
      records.find{range => range.start <= address.address && range.end >= address.address}.get.typeName
    }
    
    def getSize(address: Address): Int = {
      val range = records.find{range => range.start <= address.address && range.end >= address.address}.get
      range.end - range.start + 1
    }
    
    def allocateSpace(typeName: String, numElements: Int): Address = {
      val result = insertIndex
      insertIndex += TypeHelper.sizeof(typeName) * numElements
      records += MemRange(result, insertIndex - 1, typeName)
      Address(result)
    }
    
    def readVal(address: Int): Any = {
      val typeName = getType(Address(address))
      
      typeName match {
        case "int" => data.getInt(address)
        case "double" => data.getDouble(address)
        case "char" => data.getChar(address).toInt.toChar
      }
    }
    
    def setValue(newVal: Any, address: Address): Unit = newVal match {
      case newVal: Int => data.putInt(address.address, newVal)
      case newVal: Double => data.putDouble(address.address, newVal)
      case newVal: Char => data.putChar(address.address, newVal)
      case newVal: Boolean => data.putChar(address.address, if (newVal) 1 else 0)
      case address @ Address(addy) => data.putInt(address.address, addy)
      case array: Array[_] =>
        var i = 0
        array.foreach{element =>  
          setValue(element, Address(address.address + i))
          i += TypeHelper.sizeof(getType(Address(address.address + i)))
        }
    }
  }
}

case class Address(address: Int) {
  def +(offset: Int) = {
    Address(address + offset)
  }
}

object TypeHelper {
  val sizeof = new PartialFunction[String, Int] {
    def apply(typeName: String) = typeName match {
        case "int" => 4
        case "double" => 8
        case "float" => 4
        case "bool" => 4
        case "char" => 1
      }
    def isDefinedAt(typeName: String) = Seq("int", "double", "float", "bool", "char").contains(typeName)
  }
}

protected class Variable(stack: State#VarStack, val name: String, val typeName: String, val numElements: Int, val isPointer: Boolean) {
  
  val address: Address = stack.allocateSpace(typeName, numElements)
  
  def value: Any = {
    stack.readVal(address.address)
  }
  
  def getArray: Array[Any] = {
    var i = 0
    (0 until numElements).map{ element => 
      val result = stack.readVal(address.address + i)
      i += TypeHelper.sizeof(typeName)
      result
    }.toArray
  }
  
  def dereference: Any = stack.readVal(value.asInstanceOf[Int])
  
  def setValue(newVal: Any): Unit = {
    stack.setValue(newVal, address)
  }
  
  def setArrayValue(value: Any, index: Int) = {
    stack.setValue(value, address + index * TypeHelper.sizeof(typeName))
  }
  
  def sizeof: Int = {  
    if (isPointer) {
      stack.getSize(Address(value.asInstanceOf[Int]))
    } else {
      stack.getSize(address)
    }
  }
}

case class VarRef(name: String)

class FunctionExecutionContext(globals: Seq[Variable]) {
  
  val visited = new ListBuffer[IASTNode]()
  val variables: ListBuffer[Variable] = ListBuffer[Variable]() ++ (if (globals == null) Seq() else globals)

  def addVariable(stack: State#VarStack, theName: String, theValue: Any, theTypeName: String, isPointer: Boolean) = {
    
    val resolvedType = if (isPointer) "int" else theTypeName
    val newVar = theValue match {
      case array: Array[_] =>
        val theArray = new Variable(stack, theName + "_array", theTypeName, array.length, false)
        theArray.setValue(theValue)
        val theArrayPtr = new Variable(stack, theName, "int", 1, true)
        theArrayPtr.setValue(theArray.address)
        theArrayPtr
      case _ =>
        val newVar = new Variable(stack, theName, resolvedType, 1, isPointer)
        newVar.setValue(theValue)
        newVar
    }
    
    variables += newVar
  }

  def resolveId(id: String): Variable = {
    variables.find(_.name == id).get
  }
}


object Executor {
  def parseStatement(statement: IASTStatement, state: State, direction: Direction): Seq[IASTNode] = statement match {
    case breakStatement: IASTBreakStatement =>
      state.isBreaking = true
      Seq()
    case doWhileLoop: IASTDoStatement =>
      if (direction == Entering) {
        Seq(doWhileLoop.getBody, doWhileLoop.getCondition)
      } else {
        val shouldLoop = state.stack.pop match {
          case x: Int => x == 1
          case x: Boolean => x
        }
      
        if (shouldLoop) {
          state.clearVisited(doWhileLoop.getBody)
          state.clearVisited(doWhileLoop.getCondition)
          
          Seq(doWhileLoop.getBody, doWhileLoop.getCondition, doWhileLoop)
        } else {
          Seq()
        }
      }
    case whileLoop: IASTWhileStatement =>
      if (direction == Entering) {
        Seq(whileLoop.getCondition)
      } else {
        val shouldLoop = state.stack.pop match {
          case x: Int => x == 1
          case x: Boolean => x
        }
      
        if (shouldLoop) {
          state.clearVisited(whileLoop.getBody)
          state.clearVisited(whileLoop.getCondition)
          
          Seq(whileLoop.getBody, whileLoop.getCondition, whileLoop)
        } else {
          Seq()
        }
      }
    case ifStatement: IASTIfStatement =>
      if (direction == Entering) {
        Seq(ifStatement.getConditionExpression)
      } else {
        val result = state.stack.pop
        
        val value = result match {
          case VarRef(name) =>
            state.vars.resolveId(name).value
          case x => x
        }

        val conditionResult = value match {
          case x: Int => x == 1
          case x: Boolean => x
        }
        if (conditionResult) {
          Seq(ifStatement.getThenClause)
        } else if (ifStatement.getElseClause != null) {
          Seq(ifStatement.getElseClause)
        } else {
          Seq()
        }
      }
    case forLoop: IASTForStatement =>
      if (direction == Entering) {
        Seq(forLoop.getInitializerStatement, forLoop.getConditionExpression)
      } else {
        val shouldKeepLooping = state.stack.pop.asInstanceOf[Boolean]
      
        if (shouldKeepLooping) {
          state.clearVisited(forLoop.getBody)
          state.clearVisited(forLoop.getIterationExpression)
          state.clearVisited(forLoop.getConditionExpression)
          
          Seq(forLoop.getBody, forLoop.getIterationExpression, forLoop.getConditionExpression, forLoop)
        } else {
          Seq()
        }
      }
    case ret: IASTReturnStatement =>
      if (direction == Entering) {
        Seq(ret.getReturnValue)
      } else {
        // resolve everything before returning
        val returnVal = state.stack.pop
        state.stack.push(returnVal match {
          case VarRef(id) => state.vars.resolveId(id).value
          case int: Int => int
          case doub: Double => doub
        })
        Seq()
      }
    case decl: IASTDeclarationStatement =>
      if (direction == Entering) {
        Seq(decl.getDeclaration)
      } else {
        Seq()
      }
    case compound: IASTCompoundStatement =>
      if (direction == Entering) {
        compound.getStatements
      } else {
        Seq()
      }
    case exprStatement: IASTExpressionStatement =>
      if (direction == Entering) {
        Seq(exprStatement.getExpression)
      } else {
        Seq()
      }
  }
  
  def parseDeclarator(decl: IASTDeclarator, direction: Direction, state: State): Seq[IASTNode] = {
    if (direction == Exiting) {
      state.stack.push(decl.getName.getRawSignature)
      
      val theTypeName = state.currentType.getRawSignature

      val initial = theTypeName match {
          case "int" => 0.toInt
          case "double" => 0.0.toDouble
          case "char" => 0.toChar
          case _ => throw new Exception("No match for " + theTypeName)
      }
      
      if (decl.isInstanceOf[IASTArrayDeclarator]) {
        val name = state.stack.pop.asInstanceOf[String]
        
        state.stack.pop match {
          case size: Int =>
            val initialArray = Array.fill[Any](size)(initial)
            
            if (!state.stack.isEmpty) { 
              var i = 0
              for (i <- (size - 1) to 0 by -1) {
                val newInit = state.stack.pop
                initialArray(i) = newInit
              }
            }
            state.vars.addVariable(state.rawDataStack, name, initialArray, theTypeName, false)
          case initString: String =>
            val initialArray = Utils.stripQuotes(initString).toCharArray() :+ 0.toChar // terminating null char
            state.vars.addVariable(state.rawDataStack, name, initialArray, theTypeName, false)
        }
      } else {   
        
        val name = state.stack.pop.asInstanceOf[String]
        
        if (!decl.getPointerOperators.isEmpty) {
          if (!state.stack.isEmpty) {
            val initVal = state.stack.pop
            state.vars.addVariable(state.rawDataStack, name, initVal, theTypeName, true)
          } else {
            state.vars.addVariable(state.rawDataStack, name, 0, theTypeName, true)
          }
        } else {
          if (!state.stack.isEmpty) {
            // initial value is on the stack, set it
            state.vars.addVariable(state.rawDataStack, name, state.stack.pop, theTypeName, false)
          } else {
            state.vars.addVariable(state.rawDataStack, name, initial, theTypeName, false)
          }
        }
      }
      
      Seq()
    } else {
      decl match {
        case array: IASTArrayDeclarator =>
          Seq(Option(decl.getInitializer)).flatten ++ array.getArrayModifiers
        case _ =>
          Seq(Option(decl.getInitializer)).flatten
      }
    }
  }
  
  def step(current: IASTNode, state: State, direction: Direction): Seq[IASTNode] = {

    current match {
      case statement: IASTStatement =>
        Executor.parseStatement(statement, state, direction)
      case expression: IASTExpression =>
        Expressions.parse(expression, direction, state, state.rawDataStack)
      case array: IASTArrayModifier =>
        if (direction == Exiting) {
          Seq()
        } else {
          if (array.getConstantExpression != null) {
            Seq(array.getConstantExpression)
          } else {
            // e.g char str[] = "test"
            Seq()
          }
        }
      case param: IASTParameterDeclaration =>
        if (direction == Exiting) {
          val arg = state.stack.pop
          if (!param.getDeclarator.getPointerOperators.isEmpty) {
             state.vars.addVariable(state.rawDataStack, param.getDeclarator.getName.getRawSignature, arg.asInstanceOf[Address], param.getDeclSpecifier.getRawSignature, true)
          } else {
             state.vars.addVariable(state.rawDataStack, param.getDeclarator.getName.getRawSignature, arg, param.getDeclSpecifier.getRawSignature, false)
          }
          Seq()
        } else {
          Seq()
        }
      case tUnit: IASTTranslationUnit =>
        if (direction == Entering) {
          tUnit.getDeclarations
        } else {
          Seq()
        }
      case simple: IASTSimpleDeclaration =>
        if (direction == Entering) { 
          state.currentType = simple.getDeclSpecifier
          simple.getDeclarators
        } else {
          state.currentType = null
          Seq()
        }
      case fcnDec: IASTFunctionDeclarator =>
        if (direction == Entering) {
          fcnDec.getChildren.filter(x => !x.isInstanceOf[IASTName]).map{x => x}
        } else {
          Seq()
        }
      case decl: IASTDeclarator =>
        Executor.parseDeclarator(decl, direction, state)
      case fcnDef: IASTFunctionDefinition =>
        if (state.isPreprocessing) {
          state.functionMap += (fcnDef.getDeclarator.getName.getRawSignature -> fcnDef)
          Seq()
        } else if (direction == Exiting) {
          state.vars = state.executionContext.pop
          Seq()
        } else {
          Seq(fcnDef.getDeclarator, fcnDef.getBody)
        }
      case eq: IASTEqualsInitializer =>
        if (direction == Entering) {
          Seq(eq.getInitializerClause)
        } else {
          Seq()
        }
      case initList: IASTInitializerList =>
        if (direction == Entering) {
          initList.getClauses
        } else {
          Seq()
        }
      case typeId: IASTTypeId =>
        if (direction == Exiting) {
           state.stack.push(typeId.getDeclSpecifier.getRawSignature)
        }
        Seq()
      case spec: IASTSimpleDeclSpecifier =>
        if (direction == Entering) {
          Seq()
        } else {
          state.stack.push(spec.getRawSignature)
          Seq()
        }
    }
  }
}

class Executor(code: String) {
   
  val tUnit = Utils.getTranslationUnit(code)
  val engineState = new State

  def execute = {
    
    val pathStack = new Stack[IASTNode]()
  
    var current: IASTNode = null
    var direction: Direction = Entering

    def tick(): Unit = {
      direction = if (engineState.vars.visited.contains(current)) Exiting else Entering

      val paths: Seq[IASTNode] = Executor.step(current, engineState, direction)   
      
      if (engineState.isBreaking) {
        // unroll the path stack until we meet the first parent which is a loop
        var reverse = pathStack.pop
        while (!reverse.isInstanceOf[IASTWhileStatement] && !reverse.isInstanceOf[IASTWhileStatement]) {
          reverse = pathStack.pop
        }
        engineState.isBreaking = false
      }
      
      if (direction == Exiting) {
        pathStack.pop
      } else {
        engineState.vars.visited += current
      }
      
      paths.reverse.foreach{path => pathStack.push(path)}
      
      if (!pathStack.isEmpty) {
        current = pathStack.head
      } else {
        current = null
      }
    }
    
    def runProgram() = {
      while (current != null) {
       // println(current.getClass.getSimpleName + ":" + direction)
        tick()
      }
    }
    
    current = tUnit
    
    engineState.executionContext.push(new FunctionExecutionContext(null)) // load initial stack
    engineState.vars = engineState.executionContext.head

    runProgram()
    engineState.isPreprocessing = false
    engineState.stack.clear
    
    println("_----------------------------------------------_")
    
    engineState.globals ++= engineState.vars.variables
    
    engineState.executionContext.clear
    engineState.executionContext.push(new FunctionExecutionContext(engineState.globals)) // load initial stack
    engineState.vars = engineState.executionContext.head
    pathStack.clear
    pathStack.push(engineState.functionMap("main"))
    current = pathStack.head

    runProgram()
  }
}
