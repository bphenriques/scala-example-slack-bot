package com.bphenriques.example.slack.http

import cats.effect.IO
import cats.syntax.all._
import com.bphenriques.example.slack.model.{Form, Status}
import io.circe._
import io.circe.syntax._
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.{EntityDecoder, EntityEncoder}

import java.time.Instant

object HttpCodec {

  implicit lazy val partialFormCodec: Codec[Form.Partial] = {
    val d: Decoder[Form.Partial] =
      (Decoder[String].at("field"), Decoder[Status].at("status")).mapN(Form.Partial.apply)

    val e: Encoder[Form.Partial] = { f =>
      Json.obj(
        "field"  := f.field,
        "status" := f.action,
      )
    }
    Codec.from(d, e)
  }

  implicit lazy val formCodec: Codec[Form] = {
    val d: Decoder[Form] =
      (
        Decoder[String].at("request_id"),
        Decoder[Instant].at("request_at"),
        partialFormCodec,
      ).mapN { case (requestId, requestedAt, partial) => Form(requestId, requestedAt, partial) }

    val e: Encoder[Form] = f =>
      Json.obj(
        "request_id"   := f.requestId,
        "requested_at" := f.requestedAt,
        "field"        := f.field,
        "status"       := f.status,
      )
    Codec.from(d, e)
  }

  implicit lazy val decodeFormRequest: Decoder[HttpRequests.SubmitForm.Request] =
    partialFormCodec.map(HttpRequests.SubmitForm.Request)

  implicit lazy val encodeFormResponse: Encoder[HttpRequests.SubmitForm.Response] = _.form.asJson

  implicit val entityDecodeFormRequest: EntityDecoder[IO, HttpRequests.SubmitForm.Request] =
    jsonOf[IO, HttpRequests.SubmitForm.Request]

  implicit lazy val entityEncodeFormResponse: EntityEncoder[IO, HttpRequests.SubmitForm.Response] =
    jsonEncoderOf[HttpRequests.SubmitForm.Response]

  implicit lazy val actionCodec: Codec[Status] = {
    val d: Decoder[Status] = Decoder[String].flatMap {
      case "pending"  => Decoder.const(Status.Pending)
      case "approved" => Decoder.const(Status.Approved)
      case "rejected" => Decoder.const(Status.Rejected)
      case other      => Decoder.failedWithMessage(s"Illegal status: $other")
    }

    val e: Encoder[Status] = {
      case Status.Pending  => "pending".asJson
      case Status.Approved => "approved".asJson
      case Status.Rejected => "rejected".asJson
    }

    Codec.from(d, e)
  }
}
