package scala.astViewer

import scala.sys.process.ProcessIO
import scala.sys.process._
import better.files._
import scala.collection.mutable.ListBuffer
import java.io.OutputStream
import java.io.InputStream

object Gcc {
  
  val logger = new SyntaxLogger
  
  def checkSyntax(code: String) = {
    
    val file = File("temp.c")
    file.overwrite(code)
    
    val sourceFileTokens = Seq("-c", file.path.toString)

    val processTokens =
        Seq("gcc") ++ sourceFileTokens
  
    val builder = Process(processTokens, File("").toJava)
    val compile = builder.run(logger.process)
    compile.exitValue()
    
    logger.errors.foreach(println)
  } 
  
  def parseGccErrorString(error: String, function: String, path: Seq[String]): Option[BuildError] = {
    try {
      if ((error contains "error:") ||
        (error contains "warning:") ||
        (error contains "note:") ||
        (error contains "In file included from")) {
        
        if (error.contains("In file included from")) {
          val path = error.split("In file included from ").last
        }
  
        if (error.contains(".c:")) {
          val endOfFilePath = error.indexOf(".c:") + 2
    
          val remaining = error.substring(endOfFilePath + 1)
    
          val tokens = remaining.split(":")
          
          val file = error.substring(0, endOfFilePath)
          val line = Integer.parseInt(tokens(0))
    
          val column = Integer.parseInt(tokens(1))
          val errorType = tokens(2).trim
          Some(BuildError(Seq(ErrorLocation(File(file), line, column)), Some(function), errorType, tokens.splitAt(3)._2.reduce(_ + ":" + _).trim))
        } else if (path.isEmpty) { // immediate header issue
          val endOfFilePath = error.indexOf(".h:") + 2
    
          val remaining = error.substring(endOfFilePath + 1)
    
          val tokens = remaining.split(":")
          
          val file = error.substring(0, endOfFilePath)
          val line = Integer.parseInt(tokens(0))
    
          val column = Integer.parseInt(tokens(1))
          val errorType = tokens(2).trim
          Some(BuildError(Seq(ErrorLocation(File(file), line, column)), Some(function), errorType, tokens.splitAt(3)._2.reduce(_ + ":" + _).trim))
        } else { // nested header issue
          val endOfCFilePath = path.last.indexOf(".c:") + 2
          val fromPos = path.last.indexOf(raw"C:\")
    
          val CfilePath = path.last.substring(fromPos, endOfCFilePath)
          val Cremaining = path.last.substring(endOfCFilePath + 1)
          val Ctokens = Cremaining.split(":")
          val Cline = Integer.parseInt(Ctokens(0))
          
          val endOfFilePath = error.indexOf(".h:") + 2
    
          val remaining = error.substring(endOfFilePath + 1)
    
          val tokens = remaining.split(":")
          val file = error.substring(0, endOfFilePath)
          val line = Integer.parseInt(tokens(0))
    
          val errorLocations = path.reverse.flatMap{ location => 
            if (location contains ".h:") {
              val stripped = location.drop(location.indexOf("C:\\")).reverse.drop(1).reverse
              val endOfFilePath = stripped.indexOf(".h:") + 2
              val remaining = stripped.substring(endOfFilePath + 1)
      
              val tokens = remaining.split(":")
              val file = stripped.substring(0, endOfFilePath)
              val line = Integer.parseInt(tokens(0))
        
              //val column = Integer.parseInt(tokens(1))
              //println("Z: " + ErrorLocation(File(file), line, column))
              Some(ErrorLocation(File(file), line, 0))
            } else {
              None
            }
          }
          
          val column = Integer.parseInt(tokens(1))
          val errorType = tokens(2).trim
          val originatingCFile = File(CfilePath)
          Some(BuildError(Seq(ErrorLocation(originatingCFile, Cline, column)) ++ errorLocations ++ Seq(ErrorLocation(File(file), line, column)), None, errorType, tokens.splitAt(3)._2.reduce(_ + ":" + _).trim))
        }
      } else if (error.contains("In function") && !error.contains("At top level:")) {
        Some(BuildError(Seq(), Some(function), "Misc", error))
      } else {
        None
      }
    } catch {
      case problem: Exception => {
        println("ERROR: " + error)
        println("PATH: " + path)
        problem.printStackTrace()
        None
      }
    }
  }
}

case class ErrorLocation(file: File, line: Int, column: Int)

case class BuildError(problemPath: Seq[ErrorLocation], function: Option[String], errorType: String, error: String)

class SyntaxLogger extends Logger {
  
  val errors = new ListBuffer[BuildError]()

  def addErrors(newErrors: Seq[BuildError]) = {
    errors ++= newErrors
  }
  
  def in(stream: OutputStream) = {}
  def out(stream: InputStream) = {}
  def err(stream: InputStream) = {

    val lines = scala.io.Source.fromInputStream(stream).getLines.toSeq
    val errors = getErrors(lines)

    if (!errors.isEmpty) {

      val fixedFileNameErrors = errors.flatMap { error =>
        error.problemPath.headOption.map { err =>
          error.copy(problemPath = Seq(ErrorLocation(File(err.file.path.toString.replace(".syntax", "")), err.line, err.column)))
        }
      }

      addErrors(fixedFileNameErrors)
    }
  }
}

abstract class Logger {
  def in(stream: OutputStream): Unit
  def out(stream: InputStream): Unit
  def err(stream: InputStream): Unit

  def process = new ProcessIO(in, out, err)

  val availableErrors = ListBuffer[String]()
  var errorIsInHeaderFile = false
  val errorSource = ListBuffer[String]()
  var isParsingSourcePath = false
  var currentFunction = ""

  // Advance the state machine
  def checkForErrors(error: String): Option[BuildError] = {
    println(error)
    if (error contains "In function") {
      val start = error.indexOf("\'") + 1
      val end = error.size - 2
      currentFunction = error.substring(start, end)
      None
    } else if (error.contains("In file included from") && error.last == ',') {
      // Some errors span multiple lines, in those cases were should accumulate it
      isParsingSourcePath = true
      errorSource.clear
      errorSource += error
      None
    } else if ((error contains "from ") && error.last == ':') {
      isParsingSourcePath = false
      errorSource += error
      None
    } else if (isParsingSourcePath) {
      errorSource += error
      None
    } else if (!error.contains("<command-line>")) {
      val result = Gcc.parseGccErrorString(error, currentFunction, errorSource)
      result
    } else {
      None
    }
  }

  def addErrors(errors: Seq[BuildError])
  
  def getErrors(lines: Seq[String]): Seq[BuildError] = {
    val result = for {
      errorString <- lines
      error <- checkForErrors(errorString)
    } yield error
    
    // just to be sure everything is unique.  Didnt want to use a set
    result.distinct
  }
}