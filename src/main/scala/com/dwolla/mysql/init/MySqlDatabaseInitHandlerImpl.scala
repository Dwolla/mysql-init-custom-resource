package com.dwolla.mysql.init

import cats.effect.std.Dispatcher
import cats.effect.{Trace => _, _}
import cats.syntax.all._
import com.dwolla.mysql.init.aws.{ResourceNotFoundException, SecretsManagerAlg}
import com.dwolla.mysql.init.repositories._
import doobie._
import doobie.implicits._
import feral.lambda.INothing
import feral.lambda.cloudformation._
import org.typelevel.log4cats.Logger

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

  private def databaseAsPhysicalResourceId(db: Database): PhysicalResourceId =
    PhysicalResourceId(db.value)

  private def createOrUpdate(userPasswords: List[UserConnectionInfo], input: DatabaseMetadata): ConnectionIO[PhysicalResourceId] =
    for {
      db <- databaseRepository.createDatabase(input)
      _ <- roleRepository.createRole(input.name)
      _ <- userPasswords.traverse { userPassword =>
        userRepository.addOrUpdateUser(userPassword) >> roleRepository.addUserToRole(userPassword.user, userPassword.database)
      }
    } yield databaseAsPhysicalResourceId(db)

  private def handleCreateOrUpdate(input: DatabaseMetadata)
                                  (f: List[UserConnectionInfo] => ConnectionIO[PhysicalResourceId]): F[PhysicalResourceId] =
    for {
      userPasswords <- input.secretIds.traverse(secretsManagerAlg.getSecretAs[UserConnectionInfo])
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
      _ <- usernames.traverse(roleRepository.removeUserFromRole(_, databaseName))
      db <- databaseRepository.removeDatabase(databaseName)
      _ <- roleRepository.removeRole(databaseName)
      _ <- usernames.traverse(userRepository.removeUser)
    } yield databaseAsPhysicalResourceId(db)
}

object MySqlDatabaseInitHandlerImpl {
  def apply[F[_] : Concurrent : Logger : Dispatcher : TransactorFactory](secretsManager: SecretsManagerAlg[F]): MySqlDatabaseInitHandlerImpl[F] =
    new MySqlDatabaseInitHandlerImpl(
      secretsManager,
      DatabaseRepository[F],
      RoleRepository[F],
      UserRepository[F],
    )
}
