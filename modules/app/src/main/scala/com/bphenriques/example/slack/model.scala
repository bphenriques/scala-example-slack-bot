package com.bphenriques.example.slack

import java.time.Instant

object model {

  case class SantaClausRequest(
    requestId: String,
    requestedAt: Instant,
    gift: String,
    reason: String,
    status: Status,
  )

  object SantaClausRequest {

    def fromSubmission(requestId: String, requestedAt: Instant, command: Command.Request): SantaClausRequest =
      SantaClausRequest(requestId, requestedAt, command.gift, command.reason, Status.Pending)
  }

  object Command {
    case class Request(gift: String, reason: String)
    case class SetOutcome(id: String, outcome: Status)
  }

  sealed trait Status

  object Status {
    case object Pending  extends Status
    case object Approved extends Status
    case object Rejected extends Status
  }
}
