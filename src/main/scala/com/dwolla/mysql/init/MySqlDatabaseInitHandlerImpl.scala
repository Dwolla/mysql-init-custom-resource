package com.dwolla.mysql.init

import cats.ApplicativeThrow
import cats.data.{EitherNel, NonEmptyList}
import cats.effect.std.Dispatcher
import cats.effect.{Trace => _, _}
import cats.syntax.all._
import com.dwolla.mysql.init.InputValidationError.userList
import com.dwolla.mysql.init.MySqlDatabaseInitHandlerImpl.databaseAsPhysicalResourceId
import com.dwolla.mysql.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.mysql.init.repositories._
import doobie._
import doobie.implicits._
import feral.lambda.INothing
import feral.lambda.cloudformation._
import org.typelevel.log4cats.Logger

import scala.util.control.NoStackTrace

class MySqlDatabaseInitHandlerImpl[F[_] : Concurrent : Logger : TransactorFactory](secretsManagerAlg: SecretsManagerAlg[F],
                                                                                   databaseRepository: DatabaseRepository[ConnectionIO],
                                                                                   roleRepository: RoleRepository[ConnectionIO],
                                                                                   userRepository: UserRepository[ConnectionIO],
                                                                                  ) extends CloudFormationCustomResource[F, DatabaseMetadata, INothing] {
  override def createResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

  override def updateResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
    handleCreateOrUpdate(event)(createOrUpdate(_, event)).map(HandlerResponse(_, None))

  override def deleteResource(event: DatabaseMetadata): F[HandlerResponse[INothing]] =
    for {
      usernames <- getUsernamesFromSecrets(event.secretIds, UserRepository.usernameForDatabase(event.name))
      dbId <- removeUsersFromDatabase(usernames, event.name).transact(TransactorFactory[F].buildTransactor(event))
// TODO figure out how to implement this retry strategy (which was originally just on the user removal, but needs to operate in F[_], not ConnectionIO)
//        .recoverWith {
//          case SqlState.DependentObjectsStillExist(ex) if retries > 0 =>
//            for {
//              _ <- Logger[ConnectionIO].warn(ex)(s"Failed when removing $user")
//              _ <- Temporal[ConnectionIO].sleep(5.seconds)
//              user <- removeUser(user, retries - 1)
//            } yield user
//          case SqlState.DependentObjectsStillExist(ex) if retries == 0 =>
//            Kleisli.liftF(DependentObjectsStillExistButRetriesAreExhausted(user.value, ex).raiseError[F, Username])
//        }

    } yield HandlerResponse(dbId, None)

  private def createOrUpdate(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): ConnectionIO[PhysicalResourceId] =
    for {
      db <- databaseAsPhysicalResourceId[ConnectionIO](input.name)
      _ <- databaseRepository.createDatabase(input)
      _ <- roleRepository.createRole(input.name)
      _ <- userPasswords.traverse { userPassword =>
        userRepository.addOrUpdateUser(userPassword) >> roleRepository.addUserToRole(userPassword.user, input.name)
      }
    } yield db

  private def handleCreateOrUpdate(input: DatabaseMetadata)
                                  (f: List[UserConnectionInfo] => ConnectionIO[PhysicalResourceId]): F[PhysicalResourceId] =
    for {
      userPasswords <- input.secretIds.traverse(secretsManagerAlg.getSecretAs[UserConnectionInfo])
      _ <- List(
          ensureDatabaseConnectionInfoMatches(_, input),
          ensureNoDuplicateUsers(_),
          ensureNoIdentifiersAsReservedWords(_)
        )
        .map(_(userPasswords))
        .parSequence
        .leftMap(InputValidationException(_))
        .liftTo[F]
      id <- f(userPasswords).transact(TransactorFactory[F].buildTransactor(input))
    } yield id

  private def getUsernamesFromSecrets(secretIds: List[SecretId], fallback: Username): F[List[Username]] =
    secretIds.traverse { secretId =>
      secretsManagerAlg.getSecretAs[UserConnectionInfo](secretId)
        .map(_.user)
        .recoverWith {
          case ex: ResourceNotFoundException =>
            Logger[F].warn(ex)(s"could not retrieve secret ${secretId.value}, falling back to ${fallback.value}")
              .as(fallback)
        }
    }

  private def removeUsersFromDatabase(usernames: List[Username], databaseName: Database): ConnectionIO[PhysicalResourceId] =
    for {
      db <- databaseAsPhysicalResourceId[ConnectionIO](databaseName)
      _ <- usernames.traverse(roleRepository.removeUserFromRole(_, databaseName))
      _ <- databaseRepository.removeDatabase(databaseName)
      _ <- roleRepository.removeRole(databaseName)
      _ <- usernames.traverse(userRepository.removeUser)
    } yield db

  private def ensureDatabaseConnectionInfoMatches(users: List[UserConnectionInfo], db: DatabaseMetadata): EitherNel[InputValidationError, Unit] = {
    val mismatches =
      users
        .filterNot { uci =>
          uci.database == db.name && uci.host == db.host && uci.port == db.port
        }
        .map(_.user)

    if (mismatches.isEmpty) ().rightNel
    else WrongDatabaseConnection(mismatches, db).leftNel
  }

  private def ensureNoIdentifiersAsReservedWords(users: List[UserConnectionInfo]): EitherNel[InputValidationError, Unit] = {
    val reservedIdentifiers =
      users
        .map(_.user)
        .filter(u => ReservedWords.contains(u.value))

    if (reservedIdentifiers.isEmpty) ().rightNel
    else ReservedWordAsIdentifier(reservedIdentifiers).leftNel
  }


  private def ensureNoDuplicateUsers(users: List[UserConnectionInfo]): EitherNel[InputValidationError, Unit] = {
    val duplicates: Iterable[Username] =
      users
        .groupBy(_.user)
        .filter {
          case (_, l) => l.length > 1
        }
        .keys

    if (duplicates.isEmpty) ().rightNel
    else DuplicateUsers(duplicates).leftNel
  }
}

sealed trait InputValidationError {
  val message: String
}

object InputValidationError {
  def userList(users: Iterable[Username]): String =
    users.mkString(" - ", "\n - ", "")
}

case class InputValidationException(errors: NonEmptyList[InputValidationError]) extends RuntimeException(
  errors.map(_.message).mkString_("\n")
) with NoStackTrace

case class WrongDatabaseConnection(users: List[Username], db: DatabaseMetadata) extends InputValidationError {
  val message: String =
    s"""The specified secrets contain database connection information that doesn't match the database instance being initialized:
       |
       |Expected database instance: mysql://${db.host}:${db.port}/${db.name}"
       |
       |Mismatched users:
       |${userList(users)}""".stripMargin
}

case class DuplicateUsers(users: Iterable[Username]) extends InputValidationError {
  val message: String =
    s"""The specified secrets refer to users that share database and usernames. Deduplicate the input and try again.
       |
       |${userList(users)}""".stripMargin
}

case class ReservedWordAsIdentifier(users: List[Username]) extends InputValidationError {
  override val message: String =
    s"""The specified secrets refer to usernames that are MySQL reserved words. Change or remove those users from the input and try again.
       |
       |${userList(users)}""".stripMargin
}

object MySqlDatabaseInitHandlerImpl {
  def apply[F[_] : Concurrent : Logger : Dispatcher : TransactorFactory](secretsManager: SecretsManagerAlg[F])
                                                                        (implicit logHandler: LogHandler): MySqlDatabaseInitHandlerImpl[F] =
    new MySqlDatabaseInitHandlerImpl(
      secretsManager,
      DatabaseRepository[F],
      RoleRepository[F],
      UserRepository[F],
    )

  private[MySqlDatabaseInitHandlerImpl] def databaseAsPhysicalResourceId[F[_] : ApplicativeThrow](db: Database): F[PhysicalResourceId] =
    PhysicalResourceId(db.value).liftTo[F](new RuntimeException("Database name was invalid as Physical Resource ID"))
}
