package com.bphenriques.example.slack.http

import cats.effect._
import cats.syntax.all._
import com.bphenriques.example.slack.http.HttpCodec._
import com.bphenriques.example.slack.http.HttpRequests.SlackSlashCommandTrigger
import com.bphenriques.example.slack.services.{MyService, SlackBot}
import com.bphenriques.example.slack.slack.model.ResponseAction
import com.bphenriques.example.slack.slack.HttpCodec._
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.FormDataDecoder.formEntityDecoder
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.server.middleware.{ErrorAction, ErrorHandling}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.annotation.unused

class HttpServer(@unused service: MyService, slackBot: SlackBot, slackMiddleWare: SlackWebhookMiddleware[IO])(implicit
                                                                                                              log: StructuredLogger[IO],
) {

  def routes: HttpRoutes[IO] = slackMiddleWare.verifySlackRequest(slackRoutes) <+> healthRoutes

  val healthRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "health" => Ok() }

  // Must be answered within 3000 milliseconds: https://api.slack.com/interactivity/slash-commands#responding_basic_receipt
  val slackRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "slack" / "events" =>
      for {
        request  <- req.as[SlackSlashCommandTrigger]
        _        <- slackBot.handleSlashCommand(request)
        response <- Ok()
      } yield response
    case req @ POST -> Root / "slack" / "interactivity" =>
      for {
        _        <- req.as[ResponseAction]
        response <- Ok()
      } yield response
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
