package com.dwolla.mysql.init

case class DependentObjectsStillExistButRetriesAreExhausted(obj: String, cause: Exception)
  extends RuntimeException(s"Dependent objects still exist that prevent the removal of $obj, but the specified number of retries have been exhausted", cause)
