import sbt.internal.util.complete.Parser
import sbt.internal.util.complete.DefaultParsers._

sealed abstract class Stage(val name: String) {
  val parser: Parser[this.type] = (Space ~> token(this.toString)).map(_ => this)
}

object Stage {
  val parser: Parser[Stage] =
    token(Stage.Sandbox.parser) |
      token(Stage.DevInt.parser) |
      token(Stage.Uat.parser) |
      token(Stage.Prod.parser) |
      token(Stage.Admin.parser)

  case object Sandbox extends Stage("sandbox")
  case object DevInt extends Stage("devint")
  case object Uat extends Stage("uat")
  case object Prod extends Stage("prod")
  case object Admin extends Stage("admin")
}
