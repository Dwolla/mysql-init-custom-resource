package com.dwolla.mysql.init

import io.circe._
import io.circe.generic.semiauto._
import io.circe.refined._
import shapeless.syntax.std.product._


case class DatabaseMetadata(host: Host,
                            port: Port,
                            name: Database,
                            username: MasterDatabaseUsername,
                            password: MasterDatabasePassword,
                            secretIds: List[SecretId],
                           )

object DatabaseMetadata {
  implicit val DecodeDatabaseMetadata: Decoder[DatabaseMetadata] =
    Decoder.forProduct6("Host",
      "Port",
      "DatabaseName",
      "MasterDatabaseUsername",
      "MasterDatabasePassword",
      "UserConnectionSecrets")(DatabaseMetadata.apply)

  implicit val EncodeDatabaseMetadata: Encoder[DatabaseMetadata] =
    Encoder.forProduct6("Host",
      "Port",
      "DatabaseName",
      "MasterDatabaseUsername",
      "MasterDatabasePassword",
      "UserConnectionSecrets")(_.toTuple)
}

case class UserConnectionInfo(database: Database,
                              host: Host,
                              port: Port,
                              user: Username,
                              password: Password,
                             )

object UserConnectionInfo {
  implicit val UserConnectionInfoCodec: Codec[UserConnectionInfo] = deriveCodec[UserConnectionInfo]
}
