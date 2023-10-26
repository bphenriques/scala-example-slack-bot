package com.bphenriques.example.slack

import cats.effect._
import cats.syntax.all._
import ciris._

case class Config(
  slack: Config.Slack,
)

object Config {

  case class Slack(
    someConfig: String
  )

  object Slack {

    val value: ConfigValue[IO, Slack] = (
      env("DATABASE_USER"),
      env("DATABASE_PASSWORD").redacted,
      env("DATABASE_URL"),
      env("DATABASE_DRIVER"),
    ).parMapN(Slack.apply)
  }

  def value: ConfigValue[IO, Config] =
    Slack.value.parMapN(Config.apply)
}
