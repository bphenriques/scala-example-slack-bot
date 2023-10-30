package com.bphenriques.example.slack.http

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.{Async, Clock, Sync}
import cats.syntax.all._
import com.bphenriques.example.slack.http.model.SlackMiddleWareError._
import com.bphenriques.example.slack.http.model._
import org.http4s._
import org.http4s.client.oauth1.HmacSha256
import org.http4s.server._
import org.typelevel.ci.CIString

import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.DurationInt

trait SlackWebhookMiddleware[F[_]] {
  def verifySlackRequest: HttpMiddleware[F]
}

// Slack Token verifier: https://api.slack.com/authentication/verifying-requests-from-slack
object SlackWebhookMiddleware {

  private def MaxTsDurationMs: Long = 5.minutes.toSeconds * 1000

  def make[F[_]: Async](signingKey: String): SlackWebhookMiddleware[F] = make(Clock[F].realTimeInstant, signingKey)

  def make[F[_]: Async](timestampGen: F[Instant], signingKey: String): SlackWebhookMiddleware[F] =
    new SlackWebhookMiddleware[F] {

      override def verifySlackRequest: HttpMiddleware[F] = {
        def checkExpiredTimestamp(nowMilli: Long, headers: SlackHeader): Either[SlackMiddleWareError, Unit] =
          headers.timestamp.flatMap(ts => Either.cond(nowMilli - ts < MaxTsDurationMs, (), ExpiredTimestamp))

        def checkSignature(
          headers: SlackHeader,
          request: Request[F],
          signingKey: String,
        ): EitherT[F, SlackMiddleWareError, Unit] =
          EitherT(
            request.bodyText.compile.string.flatMap { body =>
              HmacSha256
                .generate[F](s"v0:${headers.requestTsStr}:$body", signingKey) // In Base64
                .map(Base64.getDecoder.decode)
                .map(bytes => bytes.map("%02x".format(_)).mkString) // Hexadecimal representation of the bytes
                .map(signature => s"v0=$signature")                 // full Slack's signature
                .map(expected => Either.cond(expected == headers.signature, (), InvalidSignature))
            },
          )

        (routes: HttpRoutes[F]) =>
          Kleisli { (request: Request[F]) =>
            val result: F[Option[Response[F]]] =
              (timestampGen, Sync[F].fromEither(SlackHeader(request.headers))).flatMapN { case (now, headers) =>
                EitherT
                  .fromEither(checkExpiredTimestamp(now.toEpochMilli, headers))
                  .combine(checkSignature(headers, request, signingKey))
                  .value
                  .flatMap {
                    case Left(_)  => Sync[F].pure(Option(Response[F](Status.Unauthorized))) // FIXME: Add error message
                    case Right(_) => routes(request).value
                  }
              }

            OptionT(result)
          }
      }
    }
}

object model {

  case class SlackHeader(requestTsStr: String, signature: String) {

    def timestamp: Either[SlackMiddleWareError, Long] =
      Either.catchOnly[NumberFormatException](requestTsStr.toLong * 1000).leftMap(_ => InvalidTimestamp(requestTsStr))
  }

  object SlackHeader {
    val RequestTimestamp: CIString = CIString("X-Slack-Request-Timestamp")
    val Signature: CIString        = CIString("X-Slack-Signature")

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
    case class InvalidTimestamp(ts: String) extends SlackMiddleWareError(s"The provided timestamp is invalid: $ts")
    case object ExpiredTimestamp            extends SlackMiddleWareError(s"The given timestamp has expired")
    case object InvalidSignature extends SlackMiddleWareError("The provided signature does not match the expected.")
  }
}
