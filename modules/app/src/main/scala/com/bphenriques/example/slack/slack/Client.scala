package com.bphenriques.example.slack.slack

import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxMonadError
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest
import com.slack.api.methods.request.views.{ViewsOpenRequest, ViewsPublishRequest, ViewsPushRequest, ViewsUpdateRequest}
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.views.{ViewsOpenResponse, ViewsPublishResponse, ViewsPushResponse, ViewsUpdateResponse}
import com.slack.api.methods.{AsyncMethodsClient, SlackApiException, SlackApiTextResponse}
import com.slack.api.model.ErrorResponseMetadata
import com.slack.api.{SlackConfig, Slack => JavaSlack}

import scala.jdk.CollectionConverters._

trait Slack {

  // https://api.slack.com/methods/views.open
  def openView(request: ViewsOpenRequest): IO[ViewsOpenResponse]

  // https://api.slack.com/methods/views.push
  def pushView(request: ViewsPushRequest): IO[ViewsPushResponse]

  // https://api.slack.com/methods/views.update
  def updateView(request: ViewsUpdateRequest): IO[ViewsUpdateResponse]

  // https://api.slack.com/methods/views.publish
  def publishView(request: ViewsPublishRequest): IO[ViewsPublishResponse]

  // https://api.slack.com/methods/chat.postEphemeral
  def chatPostEphemeral(request: ChatPostEphemeralRequest): IO[ChatPostEphemeralResponse]
}

object Slack {

  case class SlackApiError(message: String) extends RuntimeException(message)

  object SlackApiError {

    def from(e: SlackApiException): SlackApiError = SlackApiError(
      s"Slack Api Error: ${e.getMessage}. ${e.getResponseBody}",
    )

    def from(resp: SlackApiTextResponse, metadata: ErrorResponseMetadata): SlackApiError = SlackApiError(
      s"NOK Slack Api Response: error=${resp.getError} metadata=${metadata.getMessages.asScala.toList} warning=${resp.getWarning} provided=${resp.getProvided} needed=${resp.getNeeded}",
    )

    def from(resp: SlackApiTextResponse): SlackApiError = SlackApiError(
      s"NOK Slack Api Response: error=${resp.getError}",
    )
  }

  def fromClient(client: AsyncMethodsClient): Slack = new Slack {

    override def openView(request: ViewsOpenRequest): IO[ViewsOpenResponse] =
      adaptApiError[ViewsOpenResponse](
        IO.fromCompletableFuture(IO(client.viewsOpen(request))),
        r => SlackApiError.from(r, r.getResponseMetadata),
      )

    override def pushView(request: ViewsPushRequest): IO[ViewsPushResponse] =
      adaptApiError[ViewsPushResponse](
        IO.fromCompletableFuture(IO(client.viewsPush(request))),
        r => SlackApiError.from(r, r.getResponseMetadata),
      )

    override def updateView(request: ViewsUpdateRequest): IO[ViewsUpdateResponse] =
      adaptApiError[ViewsUpdateResponse](
        IO.fromCompletableFuture(IO(client.viewsUpdate(request))),
        r => SlackApiError.from(r, r.getResponseMetadata),
      )

    override def publishView(request: ViewsPublishRequest): IO[ViewsPublishResponse] =
      adaptApiError[ViewsPublishResponse](
        IO.fromCompletableFuture(IO(client.viewsPublish(request))),
        r => SlackApiError.from(r, r.getResponseMetadata),
      )

    override def chatPostEphemeral(request: ChatPostEphemeralRequest): IO[ChatPostEphemeralResponse] =
      adaptApiError[ChatPostEphemeralResponse](
        IO.fromCompletableFuture(IO(client.chatPostEphemeral(request))),
        SlackApiError.from,
      )

    // https://slack.dev/java-slack-sdk/guides/web-api-basics
    private def adaptApiError[A <: SlackApiTextResponse](call: IO[A], getError: A => SlackApiError): IO[A] =
      call
        .flatMap { response =>
          if (response.isOk) IO.pure(response)
          else IO.raiseError(getError(response)) // Weird but that is the behaviour.
        }
        .adaptError { case e: SlackApiException => SlackApiError.from(e) } // Adapt the error to our own
  }

  def make(token: String): Resource[IO, Slack] =
    Resource
      .make(IO.blocking(JavaSlack.getInstance(SlackConfig.DEFAULT)))(client => IO.blocking(client.close()))
      .map(_.methodsAsync(token))
      .map(fromClient)
}
