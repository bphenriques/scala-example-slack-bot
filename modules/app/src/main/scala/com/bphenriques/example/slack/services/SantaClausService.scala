package com.bphenriques.example.slack.services

import cats.effect.IO
import cats.syntax.all._
import com.bphenriques.example.slack.model.{Command, SantaClausRequest}
import org.typelevel.log4cats.StructuredLogger

import java.time.Instant

trait SantaClausService {
  def request(request: Command.Request): IO[SantaClausRequest]
  def listPossibleGifts(): IO[List[String]]
}

object SantaClausService {

  // Slack API is limited to 100...
  private val examplePresents: List[String] =
    List(
      List("Happy family", "Family trip", "Trip to the Zoo", "Go to the Beach", "See puppies"),
      (1 to 2).map(i => s"Lego $i"),
      (1 to 3).map(i => s"Puzzle 50 Piece $i"),
      (1 to 5).map(i => s"Book $i"),
      (1 to 2).map(i => s"Board Game $i"),
      (1 to 5).map(i => s"Movie $i"),
      (1 to 5).map(i => s"Plushie $i"),
      (1 to 5).map(i => s"Pentominoes $i"),
    ).map(_.toList).combineAll

  def apply(uuidGen: IO[String], instantGen: IO[Instant])(implicit log: StructuredLogger[IO]): SantaClausService =
    new SantaClausService {

      def request(request: Command.Request): IO[SantaClausRequest] =
        (uuidGen, instantGen).flatMapN { case (requestId, requestedAt) =>
          val logWithRequestId = log.addContext(Map("request_id" -> requestId))
          logWithRequestId.info(s"Registering $request") >>
            SantaClausRequest.fromSubmission(requestId, requestedAt, request).pure[IO]
        }

      def listPossibleGifts(): IO[List[String]] = log.info(s"Getting locations...") >> IO(examplePresents)
    }
}
