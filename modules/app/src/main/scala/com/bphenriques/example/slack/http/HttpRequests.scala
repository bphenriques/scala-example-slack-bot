package com.bphenriques.example.slack.http

import com.bphenriques.example.slack.model.Form

object HttpRequests {

  object SubmitForm {
    case class Request(partial: Form.Partial)
    case class Response(form: Form)
  }

  case class SlackSlashCommandTrigger(
    apiAppId: String,
    channelId: String,
    channelName: String,
    command: String,
    responseUrl: String,
    teamsDomain: String,
    text: String,
    teamId: String,
    token: String,
    triggerId: String,
    userId: String,
    userName: String,
  )
}
