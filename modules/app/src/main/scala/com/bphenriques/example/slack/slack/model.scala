package com.bphenriques.example.slack.slack

import java.time.LocalDate

object model {

  sealed trait ResponseAction

  object ResponseAction {
    case class ViewSubmission(user: User, triggerId: String, view: View) extends ResponseAction
    case class BlockActions(user: User, triggerId: String)               extends ResponseAction
    case class ViewClosed(user: User, triggerId: String)                 extends ResponseAction
  }

  case class User(id: String, username: String, name: String)

  sealed trait StateValue

  object StateValue {
    case class Str(value: String)             extends StateValue
    case class Number(value: Long)            extends StateValue
    case class Date(value: LocalDate)         extends StateValue
    case class Multiple(values: List[String]) extends StateValue
  }

  case class View(id: String, privateMetadata: String, callbackId: String, state: State)
  case class State(values: Map[String, Map[String, StateValue]])
}
