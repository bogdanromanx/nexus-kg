package ch.epfl.bluebrain.nexus.kg

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route}
import cats.data.EitherT
import cats.instances.future._
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.{Rejection, ResourceV}
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.{DOT, Triples}
import ch.epfl.bluebrain.nexus.rdf.syntax._
import ch.epfl.bluebrain.nexus.rdf.{Dot, NTriples}
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Future

package object routes {

  private[routes] def completeWithFormat(
      fetched: Future[Either[Rejection, (StatusCode, ResourceV)]]
  )(implicit format: NonBinaryOutputFormat): Route =
    completeWithFormat(EitherT(fetched))

  private def completeWithFormat(
      fetched: EitherT[Future, Rejection, (StatusCode, ResourceV)]
  )(implicit format: NonBinaryOutputFormat): Route =
    format match {
      case f: JsonLDOutputFormat =>
        implicit val format = f
        complete(fetched.value)
      case Triples =>
        implicit val format = Triples
        complete(fetched.map { case (status, resource) => status -> resource.value.graph.as[NTriples]().value }.value)
      case DOT =>
        implicit val format = DOT
        complete(fetched.map { case (status, resource) => status -> resource.value.graph.as[Dot]().value }.value)
    }

  private[routes] val read: Permission = Permission.unsafe("resources/read")

  private[routes] val schemaError =
    MalformedQueryParamRejection("schema", "The provided schema does not match the schema on the Uri")

}
