package ch.epfl.bluebrain.nexus.kg.async

import java.time.Instant
import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern.pipe
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.async.ProjectAttributesCoordinatorActor.Msg._
import ch.epfl.bluebrain.nexus.kg.async.ProjectAttributesCoordinatorActor._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.indexing.{cassandraSource, Statistics}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.FileDigestAlreadyExists
import ch.epfl.bluebrain.nexus.kg.resources.{Event, Files, Rejection, Resource}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.FetchAttributes
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.ProgressFlowElem
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import retry.CatsEffect._
import retry._
import retry.syntax.all._

/**
  * Coordinator backed by akka actor which runs the attributes stream inside the provided project
  */
//noinspection ActorMutableStateInspection
private abstract class ProjectAttributesCoordinatorActor(implicit val config: AppConfig)
    extends Actor
    with ActorLogging {

  private var child: Option[StreamSupervisor[Task, ProjectionProgress]] = None

  def receive: Receive = {
    case Start(_, project: Project) =>
      log.debug("Started attributes coordinator for project '{}'", project.show)
      context.become(initialized(project))
      child = Some(startCoordinator(project, restartOffset = false))
    case FetchProgress(uuid) => val _ = progress(uuid).runToFuture pipeTo sender()
    case other =>
      log.debug("Received non Start message '{}', ignore", other)
  }

  def initialized(project: Project): Receive = {
    case Stop(_) =>
      log.info("Attributes process for project '{}' received a stop message.", project.show)
      child.foreach(_.stop())
      child = None
      context.become(receive)

    case FetchProgress(uuid) => val _ = progress(uuid).runToFuture pipeTo sender()

    case _: Start => //ignore, it has already been started

    case other => log.error("Unexpected message received '{}'", other)
  }

  def startCoordinator(project: Project, restartOffset: Boolean): StreamSupervisor[Task, ProjectionProgress]

  private def progress(uuid: UUID): Task[Statistics] =
    child
      .map(_.state().map(_.getOrElse(NoProgress)))
      .getOrElse(Task.pure(NoProgress))
      .map(_.progress(progressName(uuid)))
      .map { p =>
        Statistics(p.processed, p.discarded, p.failed, p.processed, p.offset.asInstant, p.offset.asInstant)
      }
}

object ProjectAttributesCoordinatorActor {

  def progressName(uuid: UUID): String = s"attributes-computation-$uuid"

  private[async] sealed trait Msg {

    /**
      * @return the project unique identifier
      */
    def uuid: UUID
  }
  object Msg {

    final case class Start(uuid: UUID, project: Project) extends Msg
    final case class Stop(uuid: UUID)                    extends Msg
    final case class FetchProgress(uuid: UUID)           extends Msg
  }

  private[async] def shardExtractor(shards: Int): ExtractShardId = {
    case msg: Msg                    => math.abs(msg.uuid.hashCode) % shards toString
    case ShardRegion.StartEntity(id) => (id.hashCode                % shards) toString
  }

  private[async] val entityExtractor: ExtractEntityId = {
    case msg: Msg => (msg.uuid.toString, msg)
  }

  /**
    * Starts the ProjectDigestCoordinator shard that coordinates the running digest' streams inside the provided project
    *
    * @param files            the files operations
    * @param shardingSettings the sharding settings
    * @param shards           the number of shards to use
    */
  final def start(
      files: Files[Task],
      shardingSettings: Option[ClusterShardingSettings],
      shards: Int
  )(
      implicit
      config: AppConfig,
      fetchDigest: FetchAttributes[Task],
      as: ActorSystem,
      projections: Projections[Task, String]
  ): ActorRef = {

    val props = Props(new ProjectAttributesCoordinatorActor {

      override def startCoordinator(
          project: Project,
          restartOffset: Boolean
      ): StreamSupervisor[Task, ProjectionProgress] = {

        implicit val indexing: IndexingConfig  = config.sparql.indexing
        implicit val policy: RetryPolicy[Task] = config.storage.fileAttrRetry.retryPolicy[Task]
        implicit val logErrors: (Either[Rejection, Resource], RetryDetails) => Task[Unit] =
          (err, d) =>
            Task.pure(log.warning("Retrying on resource creation with retry details '{}' and error: '{}'", err, d))
        val name: String = progressName(project.uuid)

        val initFetchProgressF: Task[ProjectionProgress] =
          if (restartOffset) projections.recordProgress(name, NoProgress) >> Task.delay(NoProgress)
          else projections.progress(name)

        val sourceF: Task[Source[ProjectionProgress, _]] = initFetchProgressF.map {
          initial =>
            val flow = ProgressFlowElem[Task, Any]
              .collectCast[Event]
              .collect {
                case ev: Event.FileCreated => ev: Event
                case ev: Event.FileUpdated => ev: Event
              }
              .mapAsync { event =>
                implicit val subject: Subject = event.subject
                files
                  .updateFileAttrEmpty(event.id)
                  .value
                  .retryingM {
                    case Right(_) | Left(_: FileDigestAlreadyExists) => true
                    case Left(_)                                     => false
                  }
                  .map(_.toOption)

              }
              .collectSome[Resource]
              .toPersistedProgress(name, initial)

            cassandraSource(s"project=${project.uuid}", name, initial.minProgress.offset).via(flow)
        }
        StreamSupervisor.start(sourceF, name, context.actorOf)
      }
    })
    start(props, shardingSettings, shards)
  }

  private[async] final def start(props: Props, shardingSettings: Option[ClusterShardingSettings], shards: Int)(
      implicit as: ActorSystem
  ): ActorRef = {
    val settings = shardingSettings.getOrElse(ClusterShardingSettings(as)).withRememberEntities(true)
    ClusterSharding(as).start(
      "project-attributes-coordinator",
      props,
      settings,
      entityExtractor,
      shardExtractor(shards)
    )
  }

  private[async] implicit class OffsetSyntax(offset: Offset) {

    val NUM_100NS_INTERVALS_SINCE_UUID_EPOCH = 0X01B21DD213814000L

    def asInstant: Option[Instant] = offset match {
      case NoOffset | Sequence(_) => None
      case TimeBasedUUID(uuid)    =>
        //adapted from https://support.datastax.com/hc/en-us/articles/204226019-Converting-TimeUUID-Strings-to-Dates
        Some(Instant.ofEpochMilli((uuid.timestamp - NUM_100NS_INTERVALS_SINCE_UUID_EPOCH) / 10000))
    }
  }
}
