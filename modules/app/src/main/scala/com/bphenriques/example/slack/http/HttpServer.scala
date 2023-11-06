package com.bphenriques.example.slack.http

import cats.effect._
import cats.syntax.all._
import com.bphenriques.example.slack.services.SlackBot
import com.bphenriques.example.slack.slack.Http4sSlackProxy
import com.comcast.ip4s.IpLiteralSyntax
import com.slack.api.bolt.request.builtin.{BlockActionRequest, SlashCommandRequest, ViewSubmissionRequest}
import com.slack.api.bolt.response.{Response => SlackResponse}
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

  val slackRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "slack" / "events" =>
      http4sSlackProxy.handle(req) {
        case request: SlashCommandRequest => slackBot.handleSlashCommand(request)
        case other =>
          log.warn(s"Unhandled slack event: ${other.getRequestType.name()}") >> SlackResponse.ok().pure[IO]
      }
    case req @ POST -> Root / "slack" / "interactivity" =>
      http4sSlackProxy.handle(req) {
        case request: ViewSubmissionRequest => slackBot.handleViewSubmission(request)
        case request: BlockActionRequest    => slackBot.handleBlockActions(request)
        case other =>
          log.warn(s"Unhandled slack interactivity: ${other.getRequestType.name()}") >> SlackResponse.ok().pure[IO]
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
