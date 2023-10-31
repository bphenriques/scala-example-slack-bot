package com.bphenriques.example.slack.services

import cats.effect.IO
import cats.syntax.all._
import com.bphenriques.example.slack.http.HttpRequests.SlackSlashCommandTrigger
import com.bphenriques.example.slack.slack.Slack
import com.bphenriques.example.slack.slack.model.ResponseAction
import com.slack.api.methods.request.views.ViewsOpenRequest
import com.slack.api.model.block.Blocks._
import com.slack.api.model.block.composition.BlockCompositions._
import com.slack.api.model.block.element.BlockElements._
import com.slack.api.model.view.Views._
import org.typelevel.log4cats.StructuredLogger

import scala.annotation.unused

trait SlackBot {
  def handleSlashCommand(trigger: SlackSlashCommandTrigger): IO[Unit]
  def handleResponse(response: ResponseAction): IO[Unit]
}

object SlackBot {

  def make(slack: Slack, @unused service: MyService)(logger: StructuredLogger[IO]): SlackBot = new SlackBot {

    val MainFormCallBack = "SubmitRequestFormCallback"

    def handleSlashCommand(trigger: SlackSlashCommandTrigger): IO[Unit] =
      logger.info(s"Handling Slash Command ${trigger.text}") >>
        createForm(trigger.triggerId)

    private def createForm(triggerId: String): IO[Unit] = {
      val target = view(
        _.`type`("modal")
          .title(viewTitle(_.`type`("plain_text").text("Example")))
          .callbackId(MainFormCallBack)
          .submit(viewSubmit(_.`type`("plain_text").text("Create")))
          .blocks(
            asBlocks(
              section(_.text(markdownText("*Welcome to your _App's Home_* :tada:"))),
              divider(),
              input(
                _.blockId("text_input")
                  .label(plainText("Plain Text", true))
                  .element(plainTextInput(_.actionId("plain_input_action_id"))),
              ),
              input(
                _.blockId("date_input")
                  .label(plainText("Date Picker", true))
                  .element(
                    datePicker(
                      _.actionId("date_input_action_id").initialDate("1990-04-28").placeholder(plainText("When", true)),
                    ),
                  ),
              ),
              input(
                _.blockId("email_input")
                  .label(plainText("Email", true))
                  .element(emailTextInput(_.actionId("email_input_action_id"))),
              ),
              input(
                _.blockId("number_input")
                  .label(plainText("Number", true))
                  .element(numberInput(_.actionId("number_input_action_id"))),
              ),
              input(
                _.blockId("multi_select_input")
                  .label(plainText("Multi Select", true))
                  .element(
                    multiStaticSelect(
                      _.actionId("multi_select_input_action_id")
                        .options(
                          asOptions(
                            option(plainText("Option 1"), "option-1"),
                            option(plainText("Option 2"), "option-2"),
                            option(plainText("Option 3"), "option-3"),
                            option(plainText("Option 4"), "option-4"),
                            option(plainText("Option 5"), "option-5"),
                          ),
                        ),
                    ),
                  ),
              ),
            ),
          )
          .callbackId("some_call_back_id"),
      )

      // TODO: We may want to store the id to update it later on
      IO(ViewsOpenRequest.builder().view(target).triggerId(triggerId).build())
        .flatMap(slack.openView)
        .void
    }

    override def handleResponse(response: ResponseAction): IO[Unit] = {
      response match {
        case submission: ResponseAction.ViewSubmission if submission.view.callbackId == MainFormCallBack =>
          logger.info("Handing MainForm Callback")
        case r => logger.warn(s"Unhandled request received $r")
      }
    }
  }
}
