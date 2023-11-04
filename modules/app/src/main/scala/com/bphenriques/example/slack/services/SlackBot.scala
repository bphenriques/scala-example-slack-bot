package com.bphenriques.example.slack.services

import cats.effect.IO
import cats.syntax.all._
import com.bphenriques.example.slack.model.{Command, SantaClausRequest, Status}
import com.bphenriques.example.slack.services.SantaClausService.ValidReason
import com.bphenriques.example.slack.services.modals._
import com.bphenriques.example.slack.slack._
import com.bphenriques.example.slack.slack.syntax._
import com.slack.api.bolt.request.builtin.{BlockActionRequest, SlashCommandRequest, ViewSubmissionRequest}
import com.slack.api.bolt.response.Response
import com.slack.api.methods.request.chat.{ChatDeleteRequest, ChatPostEphemeralRequest, ChatPostMessageRequest}
import com.slack.api.methods.request.views.ViewsOpenRequest
import com.slack.api.model.Message
import com.slack.api.model.block.Blocks._
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.composition.BlockCompositions._
import com.slack.api.model.block.element.BlockElements._
import com.slack.api.model.view.View
import com.slack.api.model.view.Views._
import org.typelevel.log4cats.StructuredLogger

import java.util
import scala.jdk.CollectionConverters._

trait SlackBot {
  def handleSlashCommand(request: SlashCommandRequest): IO[Response]
  def handleViewSubmission(request: ViewSubmissionRequest): IO[Response]
  def handleBlockActions(request: BlockActionRequest): IO[Response]
}

object SlackBot {

  def make(slack: Slack, service: SantaClausService)(logger: StructuredLogger[IO]): SlackBot = new SlackBot {

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

    // ackWithUpdate does not work :shrug:
    override def handleViewSubmission(request: ViewSubmissionRequest): IO[Response] =
      request.getPayload.getView.getCallbackId match {
        case InitialFormStarting =>
          val serviceCall = for {
            state <- IO.fromEither(request.getPayload.getView.getState.as[modals.GiftForm])
            _     <- logger.info(s"Received the following: $state")
            registeredRequest <- service.request(
              Command.Request(request.getPayload.getUser.getUsername, state.gift, state.reason),
            )
          } yield registeredRequest

          serviceCall
            .flatMap { registeredRequest =>
              logger.info("Acknowledging...") >>
                slack.chatPostEphemeralMessage(
                  ChatPostEphemeralRequest
                    .builder()
                    .channel(request.getPayload.getUser.getId) // Instant Message
                    .user(request.getPayload.getUser.getId)    // who will see the ephemeral message
                    .text(s"We have received your request with id ${registeredRequest.requestId}! We are handling it!")
                    .build(),
                ) >>
                slack.chatPostMessage(
                  ChatPostMessageRequest
                    .builder()
                    .channel(request.getPayload.getUser.getId)
                    .text("Access Request, what would you like to do?") // push notifications
                    .metadata(
                      Message.Metadata
                        .builder()
                        .eventType(RequestOutcomeEventType)
                        .eventPayload(Map[String, AnyRef]("request_id" -> registeredRequest.requestId).asJava)
                        .build(),
                    )
                    .blocks(requestOutcomeMessageBlocks(registeredRequest))
                    .build(),
                ) >> IO(request.getContext.ack())
            }
            .recoverWith { case e: ServiceError =>
              logger.warn(s"Invalid request: ${e.getMessage}") >>
                slack.chatPostEphemeralMessage(
                  ChatPostEphemeralRequest
                    .builder()
                    .channel(request.getPayload.getUser.getId) // Instant Message
                    .user(request.getPayload.getUser.getId)    // who will see the ephemeral message
                    .text(s"Sorry, your request failed: ${e.getMessage}\n\nWould you like to to try again?")
                    .build(),
                ) >> IO(request.getContext.ack())
            }
        case other => IO(request.getContext.ackWithErrors(Map("unknown_callback_id" -> other).asJava))
      }

    def handleBlockActions(request: BlockActionRequest): IO[Response] =
      request.getPayload.getMessage.getMetadata.getEventType match {
        case RequestOutcomeEventType =>
          val handleRequest =
            for {
              requestId <- IO(
                request.getPayload.getMessage.getMetadata.getEventPayload.get("request_id").asInstanceOf[String],
              )
              outcome <- IO.fromEither(
                request.getPayload.getState.as[Status](Extractor.string(RequestOutcomeValueAddr).flatMap {
                  case "approve" => Extractor.pure(Status.Approved)
                  case "reject"  => Extractor.pure(Status.Rejected)
                  case _         => Extractor.failWithMessage("The answer has to be either 'approve' or 'reject'")
                }),
              )
              _ <- service.setOutcome(Command.SetOutcome(requestId, outcome))
              _ <- slack.chatDeleteMessage(
                ChatDeleteRequest
                  .builder()
                  .channel(request.getPayload.getContainer.getChannelId)
                  .ts(request.getPayload.getContainer.getMessageTs)
                  .build(),
              ) &> slack.chatPostMessage(
                ChatPostMessageRequest
                  .builder()
                  .channel(request.getPayload.getContainer.getChannelId)
                  .text(s"Thank you! We have set the `$requestId` to `$outcome`.")
                  .build(),
              )
            } yield ()

          (handleRequest >> IO(request.getContext.ack())).recoverWith { case e: ServiceError =>
            logger.warn(s"Invalid request: ${e.getMessage}") >>
              slack.chatPostEphemeralMessage(
                ChatPostEphemeralRequest
                  .builder()
                  .channel(request.getPayload.getUser.getId) // Instant Message
                  .user(request.getPayload.getUser.getId)    // who will see the ephemeral message
                  .text(s"Sorry, invalid response: ${e.getMessage}\n\n Please re-send your answer")
                  .build(),
              ) >> IO(request.getContext.ack())
          }
        case other => logger.warn(s"Unknown $other block action event type") >> IO(request.getContext.ack())
      }

  }
}

object modals {
  val giftAddr   = SlackState.Address("gift_select_input", "gift_select_action_id")
  val reasonAddr = SlackState.Address("why_input", "why_action_id")

  val InitialFormStarting = "SubmitRequestFormCallback"
  case class GiftForm(gift: String, reason: String)

  def requestGiftView(gifts: List[String]): View =
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
                .hint(plainText(s"Write '$ValidReason'")),
            ),
          ),
        ),
    )

  implicit lazy val giftExtractor: Extractor[GiftForm] = (
    Extractor.selectedOption(giftAddr),
    Extractor.string(reasonAddr),
  ).mapN(GiftForm.apply)

  val RequestOutcomeEventType = "request-outcome"
  val RequestOutcomeValueAddr = SlackState.Address("outcome", "input-action")

  def requestOutcomeMessageBlocks(request: SantaClausRequest): util.List[LayoutBlock] =
    asBlocks(
      section(_.text(markdownText("Psst, let's pretend you are Santa Claus..."))),
      divider(),
      section(_.text(markdownText(s"""
          |You just received a request `${request.requestId}`!
          |By: ${request.requestedByName}  
          |What: ${request.gift}
          |Why: ${request.reason}
          |""".stripMargin))),
      input(
        _.blockId(RequestOutcomeValueAddr.blockId)
          .dispatchAction(true)
          .element(
            plainTextInput(
              _.actionId(RequestOutcomeValueAddr.actionId)
                .placeholder(plainText("'approve' or 'reject'")),
            ),
          )
          .label(plainText("What would you like to do?")),
      ),
    )
}
