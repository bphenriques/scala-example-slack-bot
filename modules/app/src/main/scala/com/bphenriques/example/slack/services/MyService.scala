package com.bphenriques.example.slack.services

import cats.effect.IO
import com.bphenriques.example.slack.model.Form
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant

trait MyService {
  def register(request: Form.Partial): IO[Form]
  def listLocations(): IO[List[String]]
  def listSubLocations(location: String): IO[List[String]]
}

object MyService {

  val mockState = Map(
    "Location 1" -> List("Sub Location 1", "Sub Location 2", "Sub Location 3"),
    "Location 2" -> List("Sub Location 4", "Sub Location 5", "Sub Location 6"),
    "Location 3" -> List("Sub Location 7", "Sub Location 8", "Sub Location 9"),
  )

  def apply(
    uuidGen: IO[String],
    instantGen: IO[Instant],
  ): MyService = new MyService {
    implicit val log: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

    def register(request: Form.Partial): IO[Form] = for {
      requestId   <- uuidGen
      requestedAt <- instantGen
      logWithRequestId = log.addContext(Map("request_id" -> requestId))
      _ <- logWithRequestId.info(s"Submitting... $request")
      result = Form(requestId, requestedAt, request)
    } yield result

    def listLocations(): IO[List[String]] =
      log.info(s"Getting locations...") >>
        IO(mockState.keys.toList)

    def listSubLocations(location: String): IO[List[String]] =
      log.info(s"Getting sub locations for $location") >>
        IO(mockState.getOrElse(location, List.empty))
  }
}
