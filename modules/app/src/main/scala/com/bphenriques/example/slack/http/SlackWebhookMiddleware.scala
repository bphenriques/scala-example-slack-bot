package com.bphenriques.example.slack.http

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.{Async, Sync}
import cats.syntax.all._
import com.bphenriques.example.slack.http.model.SlackMiddleWareError._
import com.bphenriques.example.slack.http.model._
import org.http4s.client.oauth1.HmacSha256
import org.http4s.dsl.io._
import org.http4s.server._
import org.http4s.{AuthedRoutes, Headers, Request}
import org.typelevel.ci.CIString

import java.time.Instant
import scala.concurrent.duration.DurationInt

trait SlackWebhookMiddleware[F[_]] {
  def middleware: AuthMiddleware[F, Unit]
}

// Based on https://github.com/slackapi/java-slack-sdk/blob/main/slack-app-backend/src/main/java/com/slack/api/app_backend/SlackSignature.java#L77
// Copied here to avoid bundling the whole library as dependency.
// TODO:
// Either:
//   1. Ask Slack to provide a verifier publicly in a smaller module that we can bridge with.
//   2. Keep this approach but refine how we use HmacSha256 (potentially to be a part of the server as well?)
// Ability to return Unauthorized with a `WWW-Authenticate` (https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/WWW-Authenticate)
object SlackWebhookMiddleware {

  private def MaxTsDurationMs: Long = 5.minutes.toSeconds * 1000

  def make[F[_]: Async](timestampGen: F[Instant], signingKey: String): SlackWebhookMiddleware[F] =
    new SlackWebhookMiddleware[F] {

      override def middleware: AuthMiddleware[F, Unit] = {
        def validateTimestamp(nowMilli: Long, headers: SlackHeader): Either[SlackMiddleWareError, Unit] =
          headers.timestamp.flatMap(ts => Either.cond(nowMilli - ts < MaxTsDurationMs, (), expiredTimestamp))

        def verifySignature(headers: SlackHeader, request: Request[F], signingKey: String): F[Unit] =
          request.bodyText.compile.string.flatMap { body =>
            HmacSha256
              .generate[F](s"v0:${headers.requestTsStr}:$body", signingKey)
              .map(_ == headers.signature)
              .ifM(Sync[F].unit, Sync[F].raiseError(invalidSignature))
          }

        val authRequestEither: Kleisli[F, Request[F], Either[SlackMiddleWareError, Unit]] = Kleisli { request =>
          EitherT
            .liftF(
              for {
                now     <- timestampGen
                headers <- Sync[F].fromEither(SlackHeader(request.headers))
                _       <- Sync[F].fromEither(validateTimestamp(now.toEpochMilli, headers))
                _       <- verifySignature(headers, request, signingKey)
              } yield (),
            )
            .value
        }

        val onFailure: AuthedRoutes[SlackMiddleWareError, F] =
          Kleisli { _ => OptionT.liftF[F, Unit](Forbidden()) }

        AuthMiddleware(authRequestEither, onFailure)
      }
    }
}

object model {

  case class SlackHeader(requestTsStr: String, signature: String) {

    def timestamp: Either[SlackMiddleWareError, Long] =
      Either.catchOnly[NumberFormatException](requestTsStr.toLong).leftMap(_ => invalidTimestamp(requestTsStr))
  }

  object SlackHeader {
    val RequestTimestamp: CIString = CIString("X-Slack-Request-Timestamp")
    val Signature: CIString        = CIString("X-Slack-Signature")

    def apply(headers: Headers): Either[SlackMiddleWareError, SlackHeader] =
      (
        headers.get(RequestTimestamp).map(_.head.sanitizedValue.trim).toRight(invalidHeaders),
        headers.get(Signature).map(_.head.sanitizedValue.trim).toRight(invalidHeaders),
      ).mapN(SlackHeader.apply)
  }

  case class Config(signingToken: String)

  sealed abstract class SlackMiddleWareError(message: String) extends Exception(message)

  object SlackMiddleWareError {
    case object InvalidHeaders extends SlackMiddleWareError(s"The Slack headers are missing or syntactically incorrect")
    case class InvalidTimestamp(ts: String) extends SlackMiddleWareError(s"The provided timestamp is invalid: $ts")
    case object ExpiredTimestamp            extends SlackMiddleWareError(s"The given timestamp has expired")
    case object InvalidSignature extends SlackMiddleWareError("The provided signature does not match the expected.")
  }

  def invalidHeaders: SlackMiddleWareError               = InvalidHeaders
  def invalidTimestamp(ts: String): SlackMiddleWareError = InvalidTimestamp(ts)
  def expiredTimestamp: SlackMiddleWareError             = ExpiredTimestamp
  def invalidSignature: SlackMiddleWareError             = InvalidSignature
}
