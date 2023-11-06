package com.bphenriques.example.slack

import cats.effect._
import cats.syntax.all._
import ciris._

case class Config(slack: Config.Slack)

object Config {

  case class Slack(slackBotToken: String, signingKey: String)

  object Slack {
    val value: ConfigValue[IO, Slack] = (env("SLACK_BOT_TOKEN"), env("SLACK_SIGNING_SECRET")).mapN(Slack.apply)
  }

  def value: ConfigValue[IO, Config] = Slack.value.map(Config.apply)
}
