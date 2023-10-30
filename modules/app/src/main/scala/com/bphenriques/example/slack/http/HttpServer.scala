package com.bphenriques.example.slack.http

import cats.effect._
import cats.syntax.all._
import com.bphenriques.example.slack.http.HttpCodec._
import com.bphenriques.example.slack.http.HttpRequests.SlackForm
import com.bphenriques.example.slack.services.MyService
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.FormDataDecoder.formEntityDecoder
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.server.middleware.{ErrorAction, ErrorHandling}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jFactory

class HttpServer(service: MyService, slackMiddleWare: SlackWebhookMiddleware[IO])(implicit log: StructuredLogger[IO]) {

  def routes: HttpRoutes[IO] = serviceRoutes <+> slackMiddleWare.verifySlackRequest(slackRoutes) <+> healthRoutes

  val serviceRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case _ @GET -> Root / "form" / id => Ok(s"The status of $id ...")
    case req @ POST -> Root / "form" =>
      for {
        request  <- req.as[HttpRequests.SubmitForm.Request]
        result   <- service.register(request.partial)
        response <- Ok(HttpRequests.SubmitForm.Response(result))
      } yield response
  }

  val healthRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "health" => Ok() }

  val slackRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "slack" / "events" =>
    for {
      _        <- req.as[SlackForm]
      response <- Ok()
    } yield response
  }

  def run: IO[Unit] = {
    implicit val loggerFactory: Slf4jFactory[IO] = Slf4jFactory.create[IO]

    val withErrorLogging = ErrorHandling.Recover.total(
      ErrorAction.log(
        routes.orNotFound,
        messageFailureLogAction = (throwable, message) => log.info(throwable)(message),
        serviceErrorLogAction = (throwable, message) => log.info(throwable)(message),
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
