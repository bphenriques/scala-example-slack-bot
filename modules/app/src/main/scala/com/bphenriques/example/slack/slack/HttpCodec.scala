package com.bphenriques.example.slack.slack

import cats.effect.IO
import cats.syntax.all._
import com.bphenriques.example.slack.slack.model._
import io.circe.{Decoder, DecodingFailure}
import org.http4s.EntityDecoder
import org.http4s.circe.jsonOf

import java.time.LocalDate

object HttpCodec {

//   case class View(id: String, privateMetadata: String, callbackId: String, state: State)
//  case class ViewSubmission(user: User, triggerId: String, view: View) extends ResponseAction
  implicit lazy val viewDecoder: Decoder[View] = (
    Decoder[String].at("id"),
    Decoder[String].at("private_metadata"),
    Decoder[String].at("callback_id"),
    Decoder[State].at("state"),
  ).mapN(View.apply)

  implicit lazy val viewSubmissionDecoder: Decoder[ResponseAction.ViewSubmission] =
    (
      Decoder[User].at("user"),
      Decoder[String].at("trigger_id"),
      Decoder[View].at("view"),
    ).mapN(ResponseAction.ViewSubmission.apply)

  implicit lazy val responseActionCodec: Decoder[ResponseAction] = { hcursor =>
    hcursor.downField("type").as[String].flatMap {
      case "view_submission" => viewSubmissionDecoder(hcursor)
      case other             => Left(DecodingFailure(s"Unknown response action type '$other'", List.empty))
    }
  }

  implicit lazy val entityDecodeResponseAction: EntityDecoder[IO, ResponseAction] =
    jsonOf[IO, ResponseAction]

  implicit lazy val decodeUser: Decoder[User] = (
    Decoder[String].at("id"),
    Decoder[String].at("username"),
    Decoder[String].at("name"),
  ).mapN(User.apply)

  implicit lazy val stateDecoder: Decoder[State] = {
    val stateValueDecoder: Decoder[StateValue] = { hcursor =>
      hcursor.downField("type").as[String].flatMap {
        case "plain_text"       => hcursor.downField("value").as[String].map(StateValue.Str)
        case "datepicker"       => hcursor.downField("value").as[String].map(LocalDate.parse).map(StateValue.Date)
        case "email_text_input" => hcursor.downField("value").as[String].map(StateValue.Str)
        case "number_input" =>
          hcursor.downField("value").as[String].map(_.toLong).map(StateValue.Number) // TODO: may fail
        case "multi_static_select" =>
          hcursor
            .downField("selected_options")
            .values
            .getOrElse(List.empty)
            .toList
            .traverse(_.as(Decoder[String].at("value")))
            .map(StateValue.Multiple)
        case other => Left(DecodingFailure(s"Unknown input type '$other'", List.empty))
      }
    }

    val actionValueMapDecoder: Decoder[Map[String, StateValue]] = hcursor =>
      hcursor.keys
        .getOrElse(List.empty)
        .toList
        .traverse(actionKey => hcursor.downField(actionKey).as[StateValue](stateValueDecoder).map(actionKey -> _))
        .map(_.toMap)

    val blockToActionsDecode: Decoder[Map[String, Map[String, StateValue]]] = hcursor =>
      hcursor.keys
        .getOrElse(List.empty)
        .toList
        .traverse { blockId =>
          hcursor.downField(blockId).as[Map[String, StateValue]](actionValueMapDecoder).map(blockId -> _)
        }
        .map(_.toMap)

    Decoder[Map[String, Map[String, StateValue]]](blockToActionsDecode).at("values").map(State.apply)
  }
}
