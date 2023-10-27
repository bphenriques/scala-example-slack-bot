package com.bphenriques.example.slack.http

import cats.effect._
import cats.syntax.all._
import com.bphenriques.example.slack.http.HttpCodec._
import com.bphenriques.example.slack.http.HttpRequests.SlackForm
import com.bphenriques.example.slack.services.MyService
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.FormDataDecoder.formEntityDecoder
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.server.middleware.Logger
import org.http4s.{AuthedRoutes, HttpApp, HttpRoutes}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}

class HttpServer(service: MyService, slackMiddleWare: SlackWebhookMiddleware[IO]) {
  implicit val loggerFactory: Slf4jFactory[IO]    = Slf4jFactory.create[IO]
  implicit val log: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def routes: HttpRoutes[IO] = serviceRoutes <+> slackMiddleWare.middleware(slackRoutes) <+> healthRoutes

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

  val slackRoutes: AuthedRoutes[Unit, IO] = AuthedRoutes.of[Unit, IO] {
    case authRequest @ GET -> Root / "slack" / "events" as _ =>
      for {
        _    <- authRequest.req.as[SlackForm]
        response <- Ok()
      } yield response
  }

  def run: IO[Unit] = {
    val app: HttpApp[IO] = Logger.httpApp(logHeaders = true, logBody = true)(routes.orNotFound)

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(app)
      .build
      .useForever
  }
}
