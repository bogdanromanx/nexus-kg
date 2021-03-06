package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant, ZoneId}

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test
import ch.epfl.bluebrain.nexus.commons.test.io.{IOEitherValues, IOOptionValues}
import ch.epfl.bluebrain.nexus.commons.test.{ActorSystemFixture, Randomness}
import ch.epfl.bluebrain.nexus.iam.client.types.Identity._
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.cache.{AclsCache, ProjectCache, ResolverCache}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver.InProjectResolver
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, ProjectResolution, Resolver, StaticResolution}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.rdf.Iri
import io.circe.Json
import org.mockito.ArgumentMatchers.any
import org.mockito.IdiomaticMockito
import org.scalatest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class SchemasSpec
    extends ActorSystemFixture("SchemasSpec", true)
    with IOEitherValues
    with IOOptionValues
    with WordSpecLike
    with IdiomaticMockito
    with Matchers
    with OptionValues
    with EitherValues
    with Randomness
    with test.Resources
    with TestHelper
    with Inspectors {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(7 seconds, 15 milliseconds)

  private implicit val appConfig             = Settings(system).appConfig
  private implicit val clock: Clock          = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  private implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  private implicit val repo = Repo[IO].ioValue
  private val projectCache  = mock[ProjectCache[IO]]
  private val resolverCache = mock[ResolverCache[IO]]
  private val aclsCache     = mock[AclsCache[IO]]
  resolverCache.get(any[ProjectRef]) shouldReturn IO.pure(List.empty[Resolver])
  aclsCache.list shouldReturn IO.pure(AccessControlLists.empty)

  private implicit val resolution =
    new ProjectResolution[IO](repo, resolverCache, projectCache, StaticResolution(AppConfig.iriResolution), aclsCache)
  private implicit val materializer = new Materializer(resolution, projectCache)
  private val schemas: Schemas[IO]  = Schemas[IO]

  trait Base {
    implicit val subject: Subject = Anonymous
    val projectRef                = ProjectRef(genUUID)
    val base                      = Iri.absolute(s"http://example.com/base/").right.value
    val id                        = Iri.absolute(s"http://example.com/$genUUID").right.value
    lazy val resId                = Id(projectRef, id)
    val voc                       = Iri.absolute(s"http://example.com/voc/").right.value
    implicit lazy val project = Project(
      resId.value,
      "proj",
      "org",
      None,
      base,
      voc,
      Map.empty,
      projectRef.id,
      genUUID,
      1L,
      deprecated = false,
      Instant.EPOCH,
      subject.id,
      Instant.EPOCH,
      subject.id
    )
    val schema = resolverSchema deepMerge Json.obj("@id" -> Json.fromString(id.asString))

  }

  "A Schemas bundle" when {

    "performing create operations" should {

      "create a new schema" in new Base {
        val resource = schemas.create(schema).value.accepted
        resource shouldEqual ResourceF.simpleF(
          Id(projectRef, resource.id.value),
          schema,
          schema = shaclRef,
          types = Set(nxv.Schema)
        )
      }

      "create a new schema with the id passed on the call" in new Base {
        val resource = schemas.create(resId, schema).value.accepted
        resource shouldEqual ResourceF.simpleF(
          Id(projectRef, resource.id.value),
          schema,
          schema = shaclRef,
          types = Set(nxv.Schema)
        )
      }

      "prevent to create a new schema with the id passed on the call not matching the @id on the payload" in new Base {
        val otherId = Id(projectRef, genIri)
        schemas.create(otherId, schema).value.rejected[IncorrectId] shouldEqual IncorrectId(otherId.ref)
      }
    }

    "performing update operations" should {

      "update a schema" in new Base {
        schemas.create(resId, schema).value.accepted shouldBe a[Resource]
        val update = schema deepMerge Json.obj(
          "@type" -> Json.arr(Json.fromString("nxv:Schema"), Json.fromString("nxv:Resolver"))
        )

        schemas.update(resId, 1L, update).value.accepted shouldEqual
          ResourceF.simpleF(resId, update, 2L, schema = shaclRef, types = Set(nxv.Schema, nxv.Resolver))
      }

      "update a schema with circular dependency" in new Base {
        projectCache.get(projectRef) shouldReturn IO(Some(project))
        resolverCache.get(projectRef) shouldReturn IO.pure(List(InProjectResolver.default(projectRef)))

        val viewSchemaId     = resId.copy(value = genIri)
        val viewSchemaWithId = viewSchema deepMerge Json.obj("@id" -> Json.fromString(viewSchemaId.value.asString))

        schemas.create(viewSchemaId, viewSchemaWithId).value.accepted shouldBe a[Resource]

        val schemaWithImports = schema deepMerge Json.obj("imports" -> Json.fromString(viewSchemaId.value.asString))
        schemas.create(resId, schemaWithImports).value.accepted shouldBe a[Resource]

        val viewSchemaWithImports = viewSchemaWithId deepMerge Json.obj("imports" -> Json.fromString(id.asString))
        schemas.update(viewSchemaId, 1L, viewSchemaWithImports).value.accepted shouldBe a[Resource]

        val viewSchemaWithOwnImports = viewSchemaWithId deepMerge Json.obj(
          "imports" -> Json.fromString(viewSchemaId.value.asString)
        )
        schemas.update(viewSchemaId, 2L, viewSchemaWithOwnImports).value.accepted shouldBe a[Resource]
      }

      "prevent to update a schema that does not exists" in new Base {
        schemas.update(resId, 1L, schema).value.rejected[NotFound] shouldEqual NotFound(resId.ref)
      }
    }

    "performing deprecate operations" should {

      "deprecate a schema" in new Base {
        schemas.create(resId, schema).value.accepted shouldBe a[Resource]
        schemas.deprecate(resId, 1L).value.accepted shouldEqual
          ResourceF.simpleF(resId, schema, 2L, schema = shaclRef, types = Set(nxv.Schema), deprecated = true)
      }

      "prevent deprecating a schema that's already deprecated" in new Base {
        schemas.create(resId, schema).value.accepted shouldBe a[Resource]
        schemas.deprecate(resId, 1L).value.accepted shouldBe a[Resource]
        schemas.deprecate(resId, 2L).value.rejected[ResourceIsDeprecated]
      }
    }
  }
}
