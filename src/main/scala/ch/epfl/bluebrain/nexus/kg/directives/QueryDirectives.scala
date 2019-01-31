package ch.epfl.bluebrain.nexus.kg.directives

import akka.http.scaladsl.common.{NameOptionReceptacle, NameReceptacle}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.ParameterDirectives.ParamDefAux
import akka.http.scaladsl.server.{Directive0, Directive1, MalformedQueryParamRejection}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.types.search.Pagination
import ch.epfl.bluebrain.nexus.kg.KgError.InvalidOutputFormat
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.PaginationConfig
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.Compacted
import ch.epfl.bluebrain.nexus.kg.routes.{OutputFormat, SearchParams}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

object QueryDirectives {

  /**
    * @return the extracted pagination from the request query parameters or defaults to the preconfigured values.
    */
  def paginated(implicit config: PaginationConfig): Directive1[Pagination] =
    (parameter('from.as[Int] ? config.pagination.from) & parameter('size.as[Int] ? config.pagination.size)).tmap {
      case (from, size) => Pagination(from.max(0), size.max(1).min(config.sizeLimit))
    }

  /**
    * @return the extracted OutputFormat from the request query parameters or 'compacted' when not present.
    *         If the output format does not exist, it fails with [[InvalidOutputFormat]] exception.
    */
  def outputFormat: Directive1[OutputFormat] =
    (parameter('output.as[String] ? Compacted.name)).flatMap { outputName =>
      OutputFormat(outputName) match {
        case Some(output) => provide(output)
        case _            => failWith(InvalidOutputFormat(outputName))
      }
    }

  /**
    * This directive passes when the query parameter specified is not present
    *
    * @param param the parameter
    * @tparam A the type of the parameter
    */
  def noParameter[A](param: NameReceptacle[A])(
      implicit paramAux: ParamDefAux[NameOptionReceptacle[A], Directive1[Option[A]]]): Directive0 = {
    parameter(param.?).flatMap {
      case Some(_) =>
        reject(MalformedQueryParamRejection(param.name, "the provided query parameter should not be present"))
      case _ =>
        pass
    }
  }

  /**
    * @return the extracted search parameters from the request query parameters.
    */
  def searchParams(implicit project: Project): Directive1[SearchParams] =
    (parameter('deprecated.as[Boolean].?) &
      parameter('rev.as[Long].?) &
      parameter('schema.as[AbsoluteIri].?) &
      parameter('createdBy.as[AbsoluteIri].?) &
      parameter('updatedBy.as[AbsoluteIri].?) &
      parameter('type.as[VocabAbsoluteIri].*) &
      parameter('id.as[AbsoluteIri].?)).tmap {
      case (deprecated, rev, schema, createdBy, updatedBy, tpe, id) =>
        SearchParams(deprecated, rev, schema, createdBy, updatedBy, tpe.map(_.value).toList, id)
    }
}
