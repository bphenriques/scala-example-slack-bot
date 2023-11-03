package com.bphenriques.example.slack.http

import cats.effect._
import cats.syntax.all._
import com.bphenriques.example.slack.services.SlackBot
import com.bphenriques.example.slack.slack.Http4sSlackProxy
import com.comcast.ip4s.IpLiteralSyntax
import com.slack.api.bolt.request.builtin.{BlockActionRequest, SlashCommandRequest, ViewSubmissionRequest}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.server.middleware.{ErrorAction, ErrorHandling}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jFactory

class HttpServer(slackBot: SlackBot, http4sSlackProxy: Http4sSlackProxy[IO])(implicit
  log: StructuredLogger[IO],
) {

  def routes: HttpRoutes[IO] = http4sSlackProxy.verifySlackHeaders(slackRoutes) <+> healthRoutes

  val healthRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "health" => Ok() }

  // Must be answered within 3000 milliseconds: https://api.slack.com/interactivity/slash-commands#responding_basic_receipt
  val slackRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "slack" / "events" =>
      http4sSlackProxy
        .http4sToSlackRequest(req)
        .flatMap {
          case e: SlashCommandRequest => slackBot.handleSlashCommand(e).flatMap(http4sSlackProxy.slackResponseToHttp4)
          case other =>
            log.warn(s"Unhandled type of response in the events endpoint ${other.getRequestType.name()}") >> Ok()
        }
    case req @ POST -> Root / "slack" / "interactivity" =>
      http4sSlackProxy
        .http4sToSlackRequest(req)
        .flatMap {
          case viewSubmission: ViewSubmissionRequest =>
            slackBot.handleViewSubmission(viewSubmission).flatMap(http4sSlackProxy.slackResponseToHttp4)
          case blockCenas: BlockActionRequest =>
            log.info(s"Received block actions ${blockCenas.getRequestBodyAsString}") >> Ok()
          case other =>
            log.warn(s"Unhandled type of response in the interactivity endpoint ${other.getRequestType.name()}") >> Ok()
        }
  }

  def run: IO[Unit] = {
    implicit val loggerFactory: Slf4jFactory[IO] = Slf4jFactory.create[IO]

    val withErrorLogging = ErrorHandling.Recover.total(
      ErrorAction.log(
        routes.orNotFound,
        messageFailureLogAction = (throwable, message) => log.error(throwable)(message),
        serviceErrorLogAction = (throwable, message) => log.error(throwable)(message),
      ),
    )

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(withErrorLogging)
      .build
      .useForever
  }
}
