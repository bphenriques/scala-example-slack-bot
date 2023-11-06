package com.bphenriques.example.slack.slack

import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Clock, Sync}
import cats.syntax.all._
import com.bphenriques.example.slack.slack.model.SlackMiddleWareError._
import com.bphenriques.example.slack.slack.model._
import com.slack.api.app_backend.SlackSignature
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.request.{RequestHeaders, Request => SlackRequest}
import com.slack.api.bolt.response.{Response => SlackResponse}
import com.slack.api.bolt.util.SlackRequestParser
import org.http4s._
import org.http4s.server._
import org.typelevel.ci.CIString

import java.time.Instant
import scala.jdk.CollectionConverters._

trait Http4sSlackProxy[F[_]] {
  def verifySlackHeaders: HttpMiddleware[F]

  // Must be answered within 3000 milliseconds: https://api.slack.com/interactivity/slash-commands#responding_basic_receipt
  // It this a Kleisi (A => F[B])?
  def handle(request: Request[F])(handler: SlackRequest[_] => F[SlackResponse]): F[Response[F]]
}

object Http4sSlackProxy {

  def make[F[_]: Async](slackAppConfig: AppConfig): Http4sSlackProxy[F] =
    make(Clock[F].realTimeInstant, slackAppConfig)

  def make[F[_]: Async](timestampGen: F[Instant], slackAppConfig: AppConfig): Http4sSlackProxy[F] =
    new Http4sSlackProxy[F] {
      val verifier = new SlackSignature.Verifier(new SlackSignature.Generator(slackAppConfig.getSigningSecret))
      val parser   = new SlackRequestParser(slackAppConfig)

      override def verifySlackHeaders: HttpMiddleware[F] = { (routes: HttpRoutes[F]) =>
        Kleisli { (request: Request[F]) =>
          OptionT
            .liftF(
              (
                timestampGen.map(_.toEpochMilli),
                Sync[F].fromEither(SlackHeader(request.headers)),
                request.bodyText.compile.string,
              ).mapN { case (nowMilli, SlackHeader(requestTsStr, signature), body) =>
                verifier.isValid(requestTsStr, body, signature, nowMilli)
              },
            )
            .ifM(routes(request), OptionT.pure(Response[F](Status.Unauthorized)))
        }
      }

      override def handle(request: Request[F])(handler: SlackRequest[_] => F[SlackResponse]): F[Response[F]] =
        http4sToSlackRequest(request)
          .flatMap(handler)
          .flatMap(slackResponse => slackResponseToHttp4(slackResponse))

      def http4sToSlackRequest(request: Request[F]): F[SlackRequest[_]] =
        request.bodyText.compile.string
          .flatMap { requestBody =>
            val headers = request.headers.headers
              .map(h => h.name.toString -> h.value)
              .groupMap { case (name, _) => name } { case (_, value) => value }
              .map { case (k, values) => k -> values.asJava }
              .asJava

            Sync[F].delay {
              val base = SlackRequestParser.HttpRequest
                .builder()
                .requestUri(request.uri.renderString)
                .queryString(request.uri.query.multiParams.map { case (k, v) => k -> v.asJava }.asJava)
                .headers(new RequestHeaders(headers))
                .requestBody(requestBody)
              request.remoteAddr.foreach(addr => base.remoteAddress(addr.toUriString))
              base.build()
            }
          }
          .flatMap(request => Sync[F].delay(parser.parse(request)))

      def slackResponseToHttp4(response: SlackResponse): F[Response[F]] = {
        val status = Status.fromInt(response.getStatusCode.toInt).toOption.get

        // The headers are not set in the Java API despite the field Content Type being set...
        val headers = response.getHeaders.asScala.iterator.toList
          .flatMap { case (header, values) => values.asScala.map(header -> _) }
          .map { case (key, value) => Headers(Header.Raw(CIString(key), value)) }
          .combineAll ++ Headers(Header.Raw(CIString("Content-Type"), response.getContentType))

        val entity = Option(response.getBody) match {
          case Some(body) => Entity.utf8String(body)
          case None       => Entity.Empty
        }

        Response[F](status = status, headers = headers, entity = entity).pure[F]
      }
    }
}

object model {

  case class SlackHeader(requestTsStr: String, signature: String)

  object SlackHeader {
    private val RequestTimestamp: CIString = CIString("X-Slack-Request-Timestamp")
    private val Signature: CIString        = CIString("X-Slack-Signature")

    def apply(headers: Headers): Either[SlackMiddleWareError, SlackHeader] =
      (
        headers.get(RequestTimestamp).map(_.head.sanitizedValue.trim).toRight(InvalidHeaders),
        headers.get(Signature).map(_.head.sanitizedValue.trim).toRight(InvalidHeaders),
      ).mapN(SlackHeader.apply)
  }

  case class Config(signingToken: String)

  sealed abstract class SlackMiddleWareError(message: String) extends Exception(message)

  object SlackMiddleWareError {
    case object InvalidHeaders extends SlackMiddleWareError(s"The Slack headers are missing or syntactically incorrect")
  }
}
