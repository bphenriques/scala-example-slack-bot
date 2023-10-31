package com.bphenriques.example.slack

import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.UUIDGen
import cats.effect.{IO, IOApp, Resource}
import com.bphenriques.example.slack.http.{HttpServer, SlackWebhookMiddleware}
import com.bphenriques.example.slack.services.{MyService, SlackBot}
import com.bphenriques.example.slack.slack.Slack
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, StructuredLogger}

object Main extends IOApp.Simple {

  override def run: IO[Unit] = resources.use { case (httpServer, _) => httpServer.run }

  private def loggerR: Resource[IO, StructuredLogger[IO]] =
    Resource
      .makeCase(Slf4jLogger.create[IO].map(_.addContext(Map("service" -> "example_app")))) {
        case (logger, ExitCase.Errored(e)) => logger.error(e)(s"Slack Bot stopped unexpectedly")
        case (logger, _)                   => logger.info("Slack Bot is shutting down")
      }
      .evalTap(logger => logger.info(s"Slack Bot server started"))

  private def resources: Resource[IO, (HttpServer, Logger[IO])] = for {
    config <- Resource.eval(Config.value.load[IO])
    logger <- loggerR
    service         = MyService(UUIDGen[IO].randomUUID.map(_.toString), IO.realTimeInstant)
    slackMiddleWare = SlackWebhookMiddleware.make[IO](config.slack.signingKey)
    slackClient <- Slack.make(config.slack.slackBotToken)
    slackBot = SlackBot.make(slackClient, service)(logger)
    server   = new HttpServer(service, slackBot, slackMiddleWare)(logger)
  } yield (server, logger)

}
