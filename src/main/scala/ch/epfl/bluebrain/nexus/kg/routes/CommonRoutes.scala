package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, Rejection => AkkaRejection}
import cats.data.{EitherT, OptionT}
import ch.epfl.bluebrain.nexus.iam.client.Caller
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.async.DistributedCache
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.tracing._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.LabeledProject
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.routes.ResourceEncoder._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.syntax.circe.context._
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Future

private[routes] abstract class CommonRoutes(resources: Resources[Task],
                                            prefix: String,
                                            acls: FullAccessControlList,
                                            caller: Caller)(implicit wrapped: LabeledProject,
                                                            cache: DistributedCache[Task],
                                                            indexers: Clients[Task],
                                                            config: AppConfig) {

  import indexers._
  implicit val acl = acls
  implicit val c   = caller

  private[routes] val resourceName = prefix.capitalize

  implicit def additional: AdditionalValidation[Task] = AdditionalValidation.pass

  def transform(r: ResourceV): Task[ResourceV] = Task.pure(r)

  def routes: Route

  def create(schema: Ref): Route =
    (post & entity(as[Json]) & projectNotDeprecated & pathEndOrSingleSlash) { source =>
      (identity & hasPermission(resourceCreate)) { implicit ident =>
        trace(s"create$resourceName") {
          complete(Created -> resources.create(wrapped.ref, wrapped.base, schema, source).value.runToFuture)
        }
      }
    }

  def create(id: AbsoluteIri, schema: Ref): Route =
    (put & entity(as[Json]) & projectNotDeprecated & pathEndOrSingleSlash) { source =>
      (identity & hasPermission(resourceCreate)) { implicit ident =>
        trace(s"create$resourceName") {
          complete(Created -> resources.create(Id(wrapped.ref, id), schema, source).value.runToFuture)
        }
      }
    }

  def update(id: AbsoluteIri, schemaOpt: Option[Ref]): Route =
    (put & entity(as[Json]) & parameter('rev.as[Long].?) & projectNotDeprecated & pathEndOrSingleSlash) {
      case (source, Some(rev)) =>
        (identity & hasPermission(resourceWrite)) { implicit ident =>
          trace(s"update$resourceName") {
            complete(resources.update(Id(wrapped.ref, id), rev, schemaOpt, source).value.runToFuture)
          }
        }
      case (_, None) => reject()
    }

  def tag(id: AbsoluteIri, schemaOpt: Option[Ref]): Route =
    pathPrefix("tags") {
      (put & entity(as[Json]) & parameter('rev.as[Long]) & projectNotDeprecated & pathEndOrSingleSlash) { (json, rev) =>
        (identity & hasPermission(resourceWrite)) { implicit ident =>
          trace(s"addTag$resourceName") {
            val tagged = resources.tag(Id(wrapped.ref, id), rev, schemaOpt, json.addContext(tagCtxUri))
            complete(Created -> tagged.value.runToFuture)
          }
        }
      }
    }

  def deprecate(id: AbsoluteIri, schemaOpt: Option[Ref]): Route =
    (delete & parameter('rev.as[Long]) & projectNotDeprecated & pathEndOrSingleSlash) { rev =>
      (identity & hasPermission(resourceWrite)) { implicit ident =>
        trace(s"deprecate$resourceName") {
          complete(resources.deprecate(Id(wrapped.ref, id), rev, schemaOpt).value.runToFuture)
        }
      }
    }

  def fetch(id: AbsoluteIri, schemaOpt: Option[Ref]): Route =
    (get & parameter('rev.as[Long].?) & parameter('tag.?) & hasPermission(resourceRead) & pathEndOrSingleSlash) {
      (revOpt, tagOpt) =>
        val idRes = Id(wrapped.ref, id)
        trace(s"get$resourceName") {
          (revOpt, tagOpt) match {
            case (Some(_), Some(_)) => reject(simultaneousParamsRejection)
            case (Some(rev), _)     => complete(resources.fetch(idRes, rev, schemaOpt).materializeRun(Ref(id)))
            case (_, Some(tag))     => complete(resources.fetch(idRes, tag, schemaOpt).materializeRun(Ref(id)))
            case _                  => complete(resources.fetch(idRes, schemaOpt).materializeRun(Ref(id)))
          }
        }
    }

  def list(schema: Ref): Route =
    (get & parameter('deprecated.as[Boolean].?) & paginated & hasPermission(resourceRead) & pathEndOrSingleSlash) {
      (deprecated, pagination) =>
        trace(s"list$resourceName") {
          complete(cache.views(wrapped.ref).flatMap(resources.list(_, deprecated, schema.iri, pagination)).runToFuture)
        }
    }

  private implicit class OptionTaskSyntax(resource: OptionT[Task, Resource]) {
    def materializeRun(ref: => Ref): Future[Either[Rejection, ResourceV]] =
      (for {
        res          <- resource.toRight(NotFound(ref): Rejection)
        materialized <- resources.materializeWithMeta(res)
        transformed  <- EitherT.right[Rejection](transform(materialized))
      } yield transformed).value.runToFuture
  }

  private[routes] val simultaneousParamsRejection: AkkaRejection =
    validationRejection("'rev' and 'tag' query parameters cannot be present simultaneously.")

  private[routes] val resourceRead   = Permissions(Permission(s"$prefix/read"), Permission(s"$prefix/manage"))
  private[routes] val resourceWrite  = Permissions(Permission(s"$prefix/write"), Permission(s"$prefix/manage"))
  private[routes] val resourceCreate = Permissions(Permission(s"$prefix/create"), Permission(s"$prefix/manage"))

}
