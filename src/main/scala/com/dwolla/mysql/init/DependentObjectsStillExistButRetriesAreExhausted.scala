package com.dwolla.mysql.init

case class DependentObjectsStillExistButRetriesAreExhausted(cause: Throwable)
  extends RuntimeException(s"Dependent objects still exist that prevent the removal of user, but the specified number of retries have been exhausted", cause)
