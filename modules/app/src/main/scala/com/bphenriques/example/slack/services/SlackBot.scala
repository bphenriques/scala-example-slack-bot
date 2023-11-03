package com.bphenriques.example.slack.services

import cats.effect.IO
import cats.syntax.all._
import com.bphenriques.example.slack.services.states._
import com.bphenriques.example.slack.slack._
import com.bphenriques.example.slack.slack.syntax._
import com.slack.api.bolt.request.builtin.{SlashCommandRequest, ViewSubmissionRequest}
import com.slack.api.bolt.response.Response
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest
import com.slack.api.methods.request.views.ViewsOpenRequest
import com.slack.api.model.block.Blocks._
import com.slack.api.model.block.composition.BlockCompositions._
import com.slack.api.model.block.element.BlockElements._
import com.slack.api.model.view.View
import com.slack.api.model.view.Views._
import org.typelevel.log4cats.StructuredLogger

import scala.jdk.CollectionConverters._

trait SlackBot {
  def handleSlashCommand(request: SlashCommandRequest): IO[Response]
  def handleViewSubmission(response: ViewSubmissionRequest): IO[Response]
}

object SlackBot {

  def make(slack: Slack, service: SantaClausService)(logger: StructuredLogger[IO]): SlackBot = new SlackBot {

    val InitialFormStarting = "SubmitRequestFormCallback"

    def handleSlashCommand(request: SlashCommandRequest): IO[Response] =
      logger.info(s"Handling Slash Command ${request.getPayload.getCommand}") >>
        service.listPossibleGifts().flatMap { locations =>
          IO(
            ViewsOpenRequest
              .builder()
              .view(requestGiftView(locations))
              .triggerId(request.getPayload.getTriggerId)
              .build(),
          )
            .flatMap(slack.openView)
        } >> IO(request.getContext.ack())

    private def requestGiftView(gifts: List[String]): View =
      view(
        _.`type`("modal")
          .title(viewTitle(_.`type`("plain_text").text("Dear Santa..."))) // must be less than 25 characters
          .callbackId(InitialFormStarting)
          .submit(viewSubmit(_.`type`("plain_text").text("Request"))) // must be less than 25 characters
          .close(viewClose(_.`type`("plain_text").text("Close")))     // must be less than 25 characters
          .blocks(
            asBlocks(
              section(_.text(markdownText("*Ho Ho Ho*! :christmas_tree:"))),
              divider(),
              input(
                _.blockId(giftAddr.blockId)
                  .label(plainText("Would love to have... ", true))
                  .element(
                    staticSelect( // can be extended to multiStaticSelect for multiple options
                      _.actionId(giftAddr.actionId)
                        .options(asOptions(gifts.map(o => option(plainText(o), o)): _*)),
                    ),
                  ),
              ),
              input(
                _.blockId(reasonAddr.blockId)
                  .label(plainText("Because...", true))
                  .element(
                    plainTextInput(_.actionId(reasonAddr.actionId)),
                  )
                  .hint(plainText("Write 'I was a wonderful kid this year'")),
              )
            ),
          ),
      )


    override def handleViewSubmission(request: ViewSubmissionRequest): IO[Response] =
      request.getPayload.getView.getCallbackId match {
        case InitialFormStarting =>
          for {
            state <- IO.fromEither(request.getPayload.getView.getState.as[states.GiftForm])
            _     <- logger.info(s"Received the following: $state")
            response <-
              if (state.reason == "I was a wonderful kid this year") {
                logger.info("Acknowledging...") >> IO(request.getContext.ack())
              } // ackWithUpdate does not work :shrug:
              else {
                logger.warn(s"Invalid request... answering back to ${request.getPayload.getUser.getId}") >>
                  slack.chatPostEphemeral(ChatPostEphemeralRequest.builder()
                    .channel(request.getPayload.getUser.getId) // IM
                    .user(request.getPayload.getUser.getId) // who will see the ephemeral message
                    .text("Not a good enough reason!")
                    .build()) >> IO(request.getContext.ack())
               // IO(request.getContext.ackWithErrors(Map("errors" -> "Not a good reason...").asJava))
              }
          } yield response
        case other => IO(request.getContext.ackWithErrors(Map("unknown_callback_id" -> other).asJava))
      }
  }
}

object states {
  val giftAddr   = SlackState.Address("gift_select_input", "gift_select_action_id")
  val reasonAddr = SlackState.Address("why_input", "why_action_id")

  case class GiftForm(gift: String, reason: String)

  implicit lazy val giftExtractor: Extractor[GiftForm] = (
    Extractor.selectedOption(giftAddr),
    Extractor.string(reasonAddr),
  ).mapN(GiftForm.apply)
}
