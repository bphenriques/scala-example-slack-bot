package com.bphenriques.example.slack

import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.UUIDGen
import cats.effect.{IO, IOApp, Resource}
import com.bphenriques.example.slack.http.HttpServer
import com.bphenriques.example.slack.services.{SantaClausService, SlackBot}
import com.bphenriques.example.slack.slack.{Http4sSlackProxy, Slack}
import com.slack.api.bolt.AppConfig
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple {

  override def run: IO[Unit] = resources.use(httpServer => httpServer.run)

  private def loggerR: Resource[IO, StructuredLogger[IO]] =
    Resource
      .makeCase(Slf4jLogger.create[IO].map(_.addContext(Map("service" -> "example_app")))) {
        case (logger, ExitCase.Errored(e)) => logger.error(e)(s"Slack Bot stopped unexpectedly")
        case (logger, _)                   => logger.info("Slack Bot is shutting down")
      }
      .evalTap(logger => logger.info(s"Slack Bot server started"))

  private def resources: Resource[IO, HttpServer] = for {
    config <- Resource.eval(Config.value.load[IO])
    logger <- loggerR
    service = SantaClausService(UUIDGen[IO].randomUUID.map(_.toString), IO.realTimeInstant)(logger)
    slackBot <- Slack.make(config.slack.slackBotToken).map(SlackBot.make(_, service)(logger))
    http4sSlackProxy = Http4sSlackProxy.make[IO](
      AppConfig
        .builder()
        .singleTeamBotToken(config.slack.slackBotToken)
        .signingSecret(config.slack.signingKey)
        .build(),
    )
    server = new HttpServer(slackBot, http4sSlackProxy)(logger)
  } yield server
}
