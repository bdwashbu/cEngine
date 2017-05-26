package tests.scala

import tests.scala.TestClasses._
import scala.cEngine._
import scala.cEngine.cEngine._

class HelloWorld extends StandardTest {
  "hello world" should "print the correct results" in {
    implicit val state = new State
    c"""printf("Hello World!\n");"""
    assert(state.stdout == List("Hello World!"))
  }
}