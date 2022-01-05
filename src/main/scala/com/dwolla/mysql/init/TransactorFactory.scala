package com.dwolla.mysql.init

import cats.data._
import cats.effect.Async
import com.ovoenergy.natchez.extras.doobie.TracedTransactor
import doobie.util.transactor.Transactor
import natchez._

trait TransactorFactory[F[_]] {
  def buildTransactor(event: DatabaseMetadata): Transactor[F]
}

object TransactorFactory {
  def apply[F[_] : TransactorFactory]: TransactorFactory[F] = implicitly

  def instance[F[_] : Async]: TransactorFactory[F] =
    event => Transactor.fromDriverManager[F](
      "com.mysql.cj.jdbc.Driver",
      mysqlConnectionUrl(event.host, event.port, event.name),
      event.username.value,
      event.password.value
    )

  def tracedInstance[F[_] : Async](service: String): TransactorFactory[Kleisli[F, Span[F], *]] =
    event => TracedTransactor(service, TransactorFactory.instance[F].buildTransactor(event))

  private def mysqlConnectionUrl(host: Host,
                                 port: Port,
                                 database: Database): String =
    s"jdbc:mysql://$host:$port/$database"

}
