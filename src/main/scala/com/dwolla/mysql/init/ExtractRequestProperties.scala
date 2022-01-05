package com.dwolla.mysql.init

import cats.syntax.all._
import io.circe.generic.semiauto.deriveDecoder
import io.circe.refined._
import io.circe.{Decoder, HCursor}

case class DatabaseMetadata(host: Host,
                            port: Port,
                            name: Database,
                            username: MasterDatabaseUsername,
                            password: MasterDatabasePassword,
                            secretIds: List[SecretId],
                           )

object DatabaseMetadata {
  implicit val DecodeDatabaseMetadata: Decoder[DatabaseMetadata] = (c: HCursor) =>
    (c.downField("Host").as[Host],
      c.downField("Port").as[Port],
      c.downField("DatabaseName").as[Database],
      c.downField("MasterDatabaseUsername").as[MasterDatabaseUsername],
      c.downField("MasterDatabasePassword").as[MasterDatabasePassword],
      c.downField("UserConnectionSecrets").as[List[SecretId]],
      ).mapN(DatabaseMetadata.apply)
}

case class UserConnectionInfo(database: Database,
                              host: Host,
                              port: Port,
                              user: Username,
                              password: Password,
                             )

object UserConnectionInfo {
  implicit val UserConnectionInfoDecoder: Decoder[UserConnectionInfo] = deriveDecoder[UserConnectionInfo]
}
