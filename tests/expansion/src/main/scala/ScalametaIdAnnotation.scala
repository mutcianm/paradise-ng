package test.macros
import scala.meta._
import localhost.lib._

class ScalametaIdAnnotation extends ParadiseNgAnnotation {
  def apply(annottee: Tree): Tree = annottee
}