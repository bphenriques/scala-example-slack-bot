package com.bphenriques.example.slack.http

import cats.effect._
import cats.syntax.all._
import com.bphenriques.example.slack.http.HttpCodec._
import com.bphenriques.example.slack.services.MyService
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}

class HttpServer(service: MyService) {
  implicit val loggerFactory: Slf4jFactory[IO]    = Slf4jFactory.create[IO]
  implicit val log: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def routes: HttpRoutes[IO] = serviceRoutes <+> healthRoutes

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

  def run: IO[Unit] = {
    val app = Logger.httpApp(logHeaders = true, logBody = true)(routes.orNotFound)

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(app)
      .build
      .useForever
  }
}
