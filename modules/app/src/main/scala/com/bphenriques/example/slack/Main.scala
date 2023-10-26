package com.bphenriques.example.slack

import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.UUIDGen
import cats.effect.{IO, IOApp, Resource}
import com.bphenriques.example.slack.http.HttpServer
import com.bphenriques.example.slack.services.MyService
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple {

  override def run: IO[Unit] = resources.use { case (httpServer, _) => httpServer.run }

  private def loggerR: Resource[IO, Logger[IO]] =
    Resource
      .makeCase(Slf4jLogger.create[IO]) {
        case (logger, ExitCase.Errored(e)) => logger.error(e)(s"Slack Bot stopped unexpectedly")
        case (logger, _)                   => logger.info("Slack Bot is shutting down")
      }
      .evalTap(logger => logger.info(s"Slack Bot server started"))

  private def resources: Resource[IO, (HttpServer, Logger[IO])] = for {
    _ <- Resource.eval(Config.value.load[IO])
    logger <- loggerR
    service = MyService(UUIDGen[IO].randomUUID.map(_.toString), IO.realTimeInstant)
    server = new HttpServer(service)
  } yield (server, logger)

}
