package com.bphenriques.example.slack

import java.time.Instant

object model {

  case class Form(
    requestId: String,
    requestedAt: Instant,
    field: String,
    status: Status,
  )

  object Form {
    case class Partial(field: String, action: Status)

    def apply(requestId: String, requestedAt: Instant, partial: Partial): Form =
      Form(requestId, requestedAt, partial.field, partial.action)
  }

  sealed trait Status

  object Status {
    case object Pending  extends Status
    case object Approved extends Status
    case object Rejected extends Status
  }
}
