package com.bphenriques.example.slack.services

import cats.effect.IO
import cats.syntax.all._
import com.bphenriques.example.slack.slack.Slack
import com.slack.api.bolt.request.builtin.{SlashCommandRequest, ViewSubmissionRequest}
import com.slack.api.bolt.response.Response
import com.slack.api.methods.request.views.{ViewsOpenRequest, ViewsUpdateRequest}
import com.slack.api.model.block.Blocks._
import com.slack.api.model.block.composition.BlockCompositions._
import com.slack.api.model.block.element.BlockElements._
import com.slack.api.model.view.ViewState
import com.slack.api.model.view.Views._
import org.typelevel.log4cats.StructuredLogger

import scala.jdk.CollectionConverters._

trait SlackBot {
  def handleSlashCommand(request: SlashCommandRequest): IO[Response]
  def handleViewSubmission(response: ViewSubmissionRequest): IO[Response]
}

object SlackBot {

  def make(slack: Slack, service: MyService)(logger: StructuredLogger[IO]): SlackBot = new SlackBot {

    val FormStartCallBack                 = "SubmitRequestFormCallback"
    val FormStartWithSubLocationsCallBack = "FormStartWithSubLocationsCallBack"

    def handleSlashCommand(request: SlashCommandRequest): IO[Response] =
      logger.info(s"Handling Slash Command ${request.getPayload.getText}") >>
        createForm(request.getPayload.getTriggerId) >>
        IO(request.getContext.ack())

    private def createForm(triggerId: String): IO[Unit] =
      service.listLocations().flatMap { options =>
        val target = view(
          _.`type`("modal")
            .title(viewTitle(_.`type`("plain_text").text("Sample App")))
            .callbackId(FormStartCallBack)
            .submit(viewSubmit(_.`type`("plain_text").text("Create")))
            .close(viewClose(_.`type`("plain_text").text("Close")))
            .blocks(
              asBlocks(
                section(_.text(markdownText("*Welcome to your _App's Home_* :tada:"))),
                divider(),
                input(
                  _.blockId("text_input")
                    .label(plainText("What", true))
                    .element(plainTextInput(_.actionId("plain_input_action_id"))),
                ),
                input(
                  _.blockId("date_input")
                    .label(plainText("When", true))
                    .element(
                      datePicker(
                        _.actionId("date_input_action_id")
                          .initialDate("1990-04-28")
                          .placeholder(plainText("When", true)),
                      ),
                    ),
                ),
                input(
                  _.blockId("location_select_input")
                    .label(plainText("Location", true))
                    .element(
                      staticSelect(
                        _.actionId("location_select_action_id")
                          .options(asOptions(options.map(o => option(plainText(o), o)): _*)),
                      ),
                    ),
                ),
              ),
            ),
        )

        // TODO: We may want to store the id to update it later on
        IO(ViewsOpenRequest.builder().view(target).triggerId(triggerId).build())
          .flatMap(slack.openView)
          .void
      }

    override def handleViewSubmission(request: ViewSubmissionRequest): IO[Response] = {

      val state = State(request.getPayload.getView.getState)
      request.getPayload.getView.getCallbackId match {
        case FormStartCallBack =>
          for {
            location            <- state.getOption("location_select_input", "location_select_action_id")
            possibleSubLocation <- service.listSubLocations(location)
            viewId = request.getPayload.getView.getId
            viewWithSubLocations = {
              val originalView = request.getPayload.getView
              originalView.getBlocks.add(
                input(
                  _.blockId("sub_location_select_input")
                    .label(plainText("Sub Location", true))
                    .element(
                      multiStaticSelect(
                        _.actionId("sub_location_select_action_id")
                          .options(asOptions(possibleSubLocation.map(o => option(plainText(o), o)): _*)),
                      ),
                    ),
                ),
              )
              originalView.setCallbackId(FormStartWithSubLocationsCallBack)

              originalView.setId(null)
              originalView.setTeamId(null)
              originalView.setState(null)
              originalView.setHash(null)
              originalView.setAppId(null)
              originalView.setBotId(null)
              originalView.setAppInstalledTeamId(null)
              originalView.setRootViewId(null)

              originalView
            }
            _ <- slack.updateView(
              ViewsUpdateRequest
                .builder()
                .viewId(viewId)
                .view(viewWithSubLocations)
                .build(),
            )
          } yield request.getContext.ack() // ackWithUpdate does not work :shrug:
        case other => IO(request.getContext.ackWithErrors(Map("unknown_callback_id" -> other).asJava))
      }
    }
  }

  case class State(state: Map[String, Map[String, ViewState.Value]]) {

    def get(blockId: String, inputId: String, get: ViewState.Value => String): IO[String] =
      IO(get(state(blockId)(inputId))).flatTap(v =>
        IO.raiseError(new RuntimeException(s"Invalid accessor for $blockId -> $inputId")).whenA(v == null),
      )

    def getOption(blockId: String, inputId: String): IO[String] =
      get(blockId, inputId, _.getSelectedOption.getValue)
  }

  object State {

    def apply(viewState: ViewState): State =
      State(viewState.getValues.asScala.map { case (k, v) => k -> v.asScala.toMap }.toMap)
  }
}
