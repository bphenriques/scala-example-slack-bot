package com.bphenriques.example.slack.services

import cats.effect.IO
import com.bphenriques.example.slack.model.Form
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant

trait MyService {
  def register(request: Form.Partial): IO[Form]
}

object MyService {

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
  }
}
