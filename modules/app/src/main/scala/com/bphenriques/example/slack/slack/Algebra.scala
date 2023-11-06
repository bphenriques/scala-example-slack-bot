package com.bphenriques.example.slack.slack

import cats.syntax.all._
import cats.{MonadError, SemigroupK}
import com.bphenriques.example.slack.slack.Extractor.ExtractError
import com.slack.api.model.view.{ViewState => SlackViewState}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

trait Extractor[A] { self =>
  def extract(state: SlackState): Either[ExtractError, A]

  def map[B](f: A => B): Extractor[B] = new Extractor[B] {

    override def extract(state: SlackState): Either[ExtractError, B] =
      self.extract(state) match {
        case Left(error)  => error.asLeft[B]
        case Right(value) => f(value).asRight[ExtractError]
      }
  }

  def flatMap[B](f: A => Extractor[B]): Extractor[B] = new Extractor[B] {

    override def extract(state: SlackState): Either[ExtractError, B] =
      self.extract(state) match {
        case Left(error)  => error.asLeft[B]
        case Right(value) => f(value).extract(state)
      }
  }

  final def handleErrorWith(f: ExtractError => Extractor[A]): Extractor[A] = new Extractor[A] {

    override def extract(state: SlackState): Either[ExtractError, A] =
      self.extract(state) match {
        case Left(error)  => f(error).extract(state)
        case r @ Right(_) => r
      }
  }

  def or[AA >: A](d: => Extractor[AA]): Extractor[AA] = new Extractor[AA] {

    override def extract(state: SlackState): Either[ExtractError, AA] =
      self.extract(state) match {
        case Left(_)      => d.extract(state)
        case r @ Right(_) => r
      }
  }
}

object Extractor {
  sealed abstract class ExtractError(message: String) extends RuntimeException(message)

  object ExtractError {
    case class InvalidAddress(addr: SlackState.Address) extends ExtractError(s"Invalid state address $addr")
    case class NullValue(addr: SlackState.Address)      extends ExtractError(s"The state address $addr contains null")
    case class Other(message: String)                   extends ExtractError(message)
  }

  def pure[A](v: A): Extractor[A]                     = _ => v.asRight[ExtractError]
  def failWithMessage[A](error: String): Extractor[A] = fail(ExtractError.Other(error))
  def fail[A](error: ExtractError): Extractor[A]      = _ => error.asLeft[Nothing]

  private def rawGet[A](
    state: SlackState,
    address: SlackState.Address,
    get: SlackViewState.Value => A,
  ): Either[ExtractError, A] =
    state.state.get(address.blockId) match {
      case Some(actions) =>
        actions.get(address.actionId) match {
          case Some(v) => Either.cond(v != null, get(v), ExtractError.NullValue(address))
          case None    => ExtractError.InvalidAddress(address).asLeft[A]
        }
      case None => ExtractError.InvalidAddress(address).asLeft[A]
    }

  def string(at: SlackState.Address): Extractor[String] = state => rawGet(state, at, _.getValue)

  def selectedOption(at: SlackState.Address): Extractor[String] =
    state => rawGet(state, at, _.getSelectedOption.getValue)

  def selectedOptions(at: SlackState.Address): Extractor[List[String]] =
    state => rawGet(state, at, _.getSelectedOptions.asScala.toList.map(_.getValue))
}

case class SlackState(state: Map[String, Map[String, SlackViewState.Value]])

object SlackState {
  case class Address(blockId: String, actionId: String)

  def apply(viewState: SlackViewState): SlackState =
    SlackState(viewState.getValues.asScala.map { case (k, v) => k -> v.asScala.toMap }.toMap)
}

object syntax {

  implicit final def slackStateSyntax(slackViewState: SlackViewState): StateSyntaxOps =
    new StateSyntaxOps(slackViewState)

  final class StateSyntaxOps(private val slackViewState: SlackViewState) extends AnyVal {

    def as[A](implicit extractor: Extractor[A]): Either[ExtractError, A] =
      extractor.extract(SlackState(slackViewState))
  }

  implicit val semiGroupK: SemigroupK[Extractor] with MonadError[Extractor, ExtractError] =
    new SemigroupK[Extractor] with MonadError[Extractor, ExtractError] {
      override def pure[A](x: A): Extractor[A]                                         = Extractor.pure(x)
      override def combineK[A](x: Extractor[A], y: Extractor[A]): Extractor[A]         = x.or(y)
      override def flatMap[A, B](fa: Extractor[A])(f: A => Extractor[B]): Extractor[B] = fa.flatMap(f)
      override def raiseError[A](e: ExtractError): Extractor[A]                        = Extractor.fail(e)

      override def tailRecM[A, B](a: A)(f: A => Extractor[Either[A, B]]): Extractor[B] = new Extractor[B] {

        @tailrec
        def step(state: SlackState, a1: A): Either[ExtractError, B] =
          f(a1).extract(state) match {
            case l @ Left(_)     => l.asInstanceOf[Either[ExtractError, B]]
            case Right(Left(a2)) => step(state, a2)
            case Right(Right(b)) => Right(b)
          }

        override def extract(state: SlackState): Either[ExtractError, B] = step(state, a)
      }

      override def handleErrorWith[A](fa: Extractor[A])(f: ExtractError => Extractor[A]): Extractor[A] =
        fa.handleErrorWith(f)
    }
}
