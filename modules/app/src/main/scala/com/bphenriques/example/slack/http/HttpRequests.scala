package com.bphenriques.example.slack.http

import com.bphenriques.example.slack.model.Form

object HttpRequests {

  object SubmitForm {
    case class Request(partial: Form.Partial)
    case class Response(form: Form)
  }
}
