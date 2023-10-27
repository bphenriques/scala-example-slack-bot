package com.bphenriques.example.slack.http

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.{Async, Clock, Sync}
import cats.syntax.all._
import com.bphenriques.example.slack.http.model.SlackMiddleWareError._
import com.bphenriques.example.slack.http.model._
import org.http4s.client.oauth1.HmacSha256
import org.http4s.dsl.Http4sDsl
import org.http4s.server._
import org.http4s.{AuthedRoutes, Headers, Request}
import org.typelevel.ci.CIString

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.DurationInt

trait SlackWebhookMiddleware[F[_]] {
  def middleware: AuthMiddleware[F, Unit]
}

// Based on https://github.com/slackapi/java-slack-sdk/blob/main/slack-app-backend/src/main/java/com/slack/api/app_backend/SlackSignature.java#L77
// Copied here to avoid bundling the whole library as dependency.
// To read in more detail: https://typelevel.org/cats/datatypes/kleisli.html
// FIXME: The HmacSha256 between Slack's and http4s have different result.
// TODO: Consider asking Slack to provide a verifier publicly in a smaller module that we can bridge with.
// TODO: Fix the server returning 500 rather than the http code in the onError
// TODO: Use Unauthorized that in turns requires a  `WWW-Authenticate`, in turn a Challenge.
object SlackWebhookMiddleware {

  private def MaxTsDurationMs: Long = 5.minutes.toSeconds * 1000

  def make[F[_]: Async](signingKey: String): SlackWebhookMiddleware[F] =
    make(Clock[F].realTimeInstant, HmacSHA256.apply[F], signingKey)

  def make[F[_]: Async](timestampGen: F[Instant], signingKey: String): SlackWebhookMiddleware[F] =
    make(timestampGen, HmacSHA256.apply[F], signingKey)

  def make[F[_]: Async](
    timestampGen: F[Instant],
    hmacSHA256: HmacSHA256[F],
    signingKey: String,
  ): SlackWebhookMiddleware[F] =
    new SlackWebhookMiddleware[F] {

      override def middleware: AuthMiddleware[F, Unit] = {
        def validateTimestamp(nowMilli: Long, headers: SlackHeader): Either[SlackMiddleWareError, Unit] =
          headers.timestamp.flatMap(ts => Either.cond(nowMilli - ts < MaxTsDurationMs, (), ExpiredTimestamp))

        def verifySignature(headers: SlackHeader, request: Request[F], signingKey: String): F[Unit] =
          request.bodyText.compile.string.flatMap { body =>
            hmacSHA256
              .generate(s"v0:${headers.requestTsStr}:$body", signingKey)
              .map(signature => s"v0=$signature") // full signature
              .map(_ == headers.signature)
              .ifM(Sync[F].unit, Sync[F].raiseError(InvalidSignature))
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

        val onFailure: AuthedRoutes[SlackMiddleWareError, F] = {
          val dsl = new Http4sDsl[F] {}
          import dsl._
          Kleisli(req => OptionT.liftF(Forbidden(req.context.getMessage)))
        }

        AuthMiddleware(authRequestEither, onFailure)
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

trait HmacSHA256[F[_]] {
  def generate(string: String, key: String): F[String]
}

// Should be the same as http4s but it does not match in the end, so this one is base on
// https://github.com/slackapi/java-slack-sdk/blob/main/slack-app-backend/src/main/java/com/slack/api/app_backend/SlackSignature.java#L103C36-L103C49
object HmacSHA256 {

  def apply[F[_]: Sync]: HmacSHA256[F] = new HmacSHA256[F] {
    val algorithm = "HmacSHA256"

    override def generate(string: String, key: String): F[String] =
      for {
        mac <- Sync[F].delay {
          val keySpec = new SecretKeySpec(key.getBytes, algorithm)
          val mac     = Mac.getInstance(algorithm)
          mac.init(keySpec)
          mac
        }
        macBytes <- Sync[F].delay(mac.doFinal(string.getBytes))
        hashValue = macBytes.foldLeft(new StringBuilder(2 * macBytes.length)) { case (ac, macByte) =>
          ac.append(String.format("%02x", macByte & 0xff))
        }
      } yield hashValue.result()
  }

  // Should work but does not.
  def http4s[F[_]: Async]: HmacSHA256[F] = HmacSha256.generate[F](_, _)
}
