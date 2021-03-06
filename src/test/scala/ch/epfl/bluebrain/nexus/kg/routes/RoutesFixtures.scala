package ch.epfl.bluebrain.nexus.kg.routes

import java.time.Instant
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import ch.epfl.bluebrain.nexus.admin.client.types.{Organization, Project}
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.iam.client.types.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts.resourceCtxUri
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Projection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source
import ch.epfl.bluebrain.nexus.kg.indexing.View.{CompositeView, ElasticSearchView, Filter, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resources.{Id, OrganizationRef, ProjectLabel, ProjectRef, Ref}
import ch.epfl.bluebrain.nexus.kg.{urlEncode, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.instances._
import ch.epfl.bluebrain.nexus.rdf.syntax._
import io.circe.Json
import io.circe.syntax._

trait RoutesFixtures extends TestHelper with Resources {

  val user                              = User("dmontero", "realm")
  implicit val caller: Caller           = Caller(user, Set(Anonymous))
  implicit val token: Option[AuthToken] = Some(AuthToken("valid"))

  val oauthToken = OAuth2BearerToken("valid")

  val defaultPrefixMapping: Map[String, AbsoluteIri] = Map(
    "resource"        -> unconstrainedSchemaUri,
    "schema"          -> shaclSchemaUri,
    "view"            -> viewSchemaUri,
    "resolver"        -> resolverSchemaUri,
    "file"            -> fileSchemaUri,
    "storage"         -> storageSchemaUri,
    "nxv"             -> nxv.base,
    "documents"       -> nxv.defaultElasticSearchIndex,
    "graph"           -> nxv.defaultSparqlIndex,
    "defaultResolver" -> nxv.defaultResolver,
    "defaultStorage"  -> nxv.defaultStorage
  )

  val mappings: Map[String, AbsoluteIri] =
    Map(
      "nxv"      -> nxv.base,
      "resource" -> unconstrainedSchemaUri,
      "view"     -> viewSchemaUri,
      "resolver" -> resolverSchemaUri
    )

  val organization = genString(length = 4)
  val project      = genString(length = 4)

  // format: off
  val organizationMeta = Organization(genIri, organization, Some("description"), genUUID, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
  // format: on

  val organizationRef     = OrganizationRef(organizationMeta.uuid)
  val genUuid             = genUUID
  val projectRef          = ProjectRef(genUUID)
  val id                  = Id(projectRef, nxv.withSuffix(genUuid.toString))
  val urlEncodedId        = urlEncode(id.value)
  val urlEncodedIdNoColon = urlEncode(id.value).replace("%3A", ":")
  val label               = ProjectLabel(organization, project)

  // format: off
  val projectMeta = Project(id.value, project, organization, None, url"http://example.com/", nxv.base, mappings, projectRef.id, organizationRef.id, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)

  val defaultEsView = ElasticSearchView(Json.obj(), Filter(), false, true, projectRef, nxv.defaultElasticSearchIndex.value, genUUID, 1L, false)
  val defaultSparqlView = SparqlView.default(projectRef)
  val compositeView = CompositeView(Source(Filter(), includeMetadata = false), Set(SparqlProjection("", defaultSparqlView), ElasticSearchProjection("", defaultEsView, Json.obj())), projectRef, genIri, genUUID, 1L, deprecated = false)
  // format: on

  implicit val finalProject = projectMeta.copy(apiMappings = projectMeta.apiMappings ++ defaultPrefixMapping)

  def tag(rev: Long, tag: String) = Json.obj("tag" -> Json.fromString(tag), "rev" -> Json.fromLong(rev))

  def response(schema: Ref, deprecated: Boolean = false)(implicit config: AppConfig): Json =
    Json
      .obj(
        "@id"            -> Json.fromString(s"nxv:$genUuid"),
        "_constrainedBy" -> schema.iri.asJson,
        "_createdAt"     -> Json.fromString(Instant.EPOCH.toString),
        "_createdBy" -> Json
          .fromString(s"${config.iam.basePublicIri.asUri}/realms/${user.realm}/users/${user.subject}"),
        "_deprecated" -> Json.fromBoolean(deprecated),
        "_rev"        -> Json.fromLong(1L),
        "_project" -> Json
          .fromString(s"${config.admin.publicIri.asUri}/${config.admin.prefix}/projects/$organization/$project"),
        "_updatedAt" -> Json.fromString(Instant.EPOCH.toString),
        "_updatedBy" -> Json.fromString(s"${config.iam.basePublicIri.asUri}/realms/${user.realm}/users/${user.subject}")
      )
      .addContext(resourceCtxUri)

  def listingResponse()(implicit config: AppConfig): Json = Json.obj(
    "@context" -> Json.arr(
      Json.fromString("https://bluebrain.github.io/nexus/contexts/search.json"),
      Json.fromString("https://bluebrain.github.io/nexus/contexts/resource.json")
    ),
    "_total" -> Json.fromInt(5),
    "_results" -> Json.arr(
      (1 to 5).map(i => {
        val id = s"${config.http.publicUri}/resources/$organization/$project/resource/resource:$i"
        jsonContentOf("/resources/es-metadata.json", Map(quote("{id}") -> id.toString))
      }): _*
    )
  )
}
