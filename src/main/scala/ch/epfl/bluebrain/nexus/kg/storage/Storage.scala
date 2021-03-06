package ch.epfl.bluebrain.nexus.kg.storage

import java.net.Proxy.Type
import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.file.{Path, Paths}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3
import akka.stream.alpakka.s3.{ApiVersion, MemoryBufferType}
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.rdf.instances._
import ch.epfl.bluebrain.nexus.iam.client.types.Permission
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResourceFormat
import ch.epfl.bluebrain.nexus.kg.resources.StorageReference._
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{ProjectRef, Rejection, ResId, ResourceV, StorageReference}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations._
import ch.epfl.bluebrain.nexus.kg.storage.Storage.{FetchFile, FetchFileAttributes, LinkFile, SaveFile, VerifyStorage}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.encoder.NodeEncoder
import ch.epfl.bluebrain.nexus.rdf.encoder.NodeEncoder.stringEncoder
import ch.epfl.bluebrain.nexus.rdf.encoder.NodeEncoderError.IllegalConversion
import ch.epfl.bluebrain.nexus.rdf.syntax._
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import ch.epfl.bluebrain.nexus.storage.client.config.StorageClientConfig
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import com.amazonaws.auth._
import com.amazonaws.regions.AwsRegionProvider

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Contract for different types of storage back-end.
  */
sealed trait Storage { self =>

  /**
    * @return a reference to the project that the store belongs to
    */
  def ref: ProjectRef

  /**
    * @return the user facing store id
    */
  def id: AbsoluteIri

  /**
    * @return the store revision
    */
  def rev: Long

  /**
    * @return the deprecation state of the store
    */
  def deprecated: Boolean

  /**
    * @return ''true'' if this store is the project's default backend, ''false'' otherwise
    */
  def default: Boolean

  /**
    *
    * @return the digest algorithm, e.g. "SHA-256"
    */
  def algorithm: String

  /**
    * @return a generated name that uniquely identifies the store and its current revision
    */
  def name: String = s"${ref.id}_${id.asString}_$rev"

  /**
    * @return the permission required in order to download a file from this storage
    */
  def readPermission: Permission

  /**
    * @return the permission required in order to upload a file to this storage
    */
  def writePermission: Permission

  /**
    * Provides a [[SaveFile]] instance.
    *
    */
  def save[F[_], In](implicit save: Save[F, In]): SaveFile[F, In] = save(self)

  /**
    * Provides a [[FetchFile]] instance.
    */
  def fetch[F[_], Out](implicit fetch: Fetch[F, Out]): FetchFile[F, Out] = fetch(self)

  /**
    * Provides a [[LinkFile]] instance.
    */
  def link[F[_]](implicit link: Link[F]): LinkFile[F] = link(self)

  /**
    * Provides a [[VerifyStorage]] instance.
    */
  def isValid[F[_]](implicit verify: Verify[F]): VerifyStorage[F] = verify(self)

  /**
    * Provides a [[FetchFileAttributes]] instance.
    *
    */
  def fetchAttributes[F[_]](implicit fetchFile: FetchAttributes[F]): FetchFileAttributes[F] = fetchFile(self)

  /**
    * A storage reference
    */
  def reference: StorageReference

  /**
    * the maximum allowed file size (in bytes) for uploaded files
    */
  def maxFileSize: Long

  /**
    * @return a new storage with the same values as the current but encrypting the sensitive information
    */
  def encrypt(implicit config: StorageConfig): Storage

  /**
    * @return a new storage with the same values as the current but decrypting the sensitive information
    */
  def decrypt(implicit config: StorageConfig): Storage

}

object Storage {

  val write: Permission = Permission.unsafe("storages/write")

  /**
    * A disk storage
    *
    * @param ref             a reference to the project that the store belongs to
    * @param id              the user facing store id
    * @param rev             the store revision
    * @param deprecated      the deprecation state of the store
    * @param default         ''true'' if this store is the project's default backend, ''false'' otherwise
    * @param algorithm       the digest algorithm, e.g. "SHA-256"
    * @param volume          the volume this storage is going to use to save files
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class DiskStorage(
      ref: ProjectRef,
      id: AbsoluteIri,
      rev: Long,
      deprecated: Boolean,
      default: Boolean,
      algorithm: String,
      volume: Path,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends Storage {

    def reference: StorageReference                      = DiskStorageReference(id, rev)
    def encrypt(implicit config: StorageConfig): Storage = this
    def decrypt(implicit config: StorageConfig): Storage = this
  }

  object DiskStorage {

    /**
      * Default [[DiskStorage]] that gets created for every project.
      *
      * @param ref the project unique identifier
      */
    def default(ref: ProjectRef)(implicit config: StorageConfig): DiskStorage =
      DiskStorage(
        ref,
        nxv.defaultStorage,
        1L,
        deprecated = false,
        default = true,
        config.disk.digestAlgorithm,
        config.disk.volume,
        config.disk.readPermission,
        config.disk.writePermission,
        config.disk.maxFileSize
      )
  }

  /**
    * An remote disk storage
    *
    * @param ref             a reference to the project that the store belongs to
    * @param id              the user facing store id
    * @param rev             the store revision
    * @param deprecated      the deprecation state of the store
    * @param default         ''true'' if this store is the project's default backend, ''false'' otherwise
    * @param algorithm       the digest algorithm, e.g. "SHA-256"
    * @param endpoint        the endpoint for the remote storage
    * @param credentials     the optional credentials to access the remote storage service
    * @param folder          the rootFolder for this storage
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class RemoteDiskStorage(
      ref: ProjectRef,
      id: AbsoluteIri,
      rev: Long,
      deprecated: Boolean,
      default: Boolean,
      algorithm: String,
      endpoint: Uri,
      credentials: Option[String],
      folder: String,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends Storage {

    def reference: StorageReference = RemoteDiskStorageReference(id, rev)

    def client[F[_]: Effect](implicit as: ActorSystem): StorageClient[F] = {
      implicit val config = StorageClientConfig(url"$endpoint".value)
      StorageClient[F]
    }

    def encrypt(implicit config: StorageConfig): Storage = copy(credentials = credentials.map(_.encrypt))

    def decrypt(implicit config: StorageConfig): Storage = copy(credentials = credentials.map(_.decrypt))

  }

  /**
    * An Amazon S3 compatible storage
    *
    * @param ref             a reference to the project that the store belongs to
    * @param id              the user facing store id
    * @param rev             the store revision
    * @param deprecated      the deprecation state of the store
    * @param default         ''true'' if this store is the project's default backend, ''false'' otherwise
    * @param algorithm       the digest algorithm, e.g. "SHA-256"
    * @param bucket          the bucket
    * @param settings        an instance of [[S3Settings]] with proper credentials to access the bucket
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class S3Storage(
      ref: ProjectRef,
      id: AbsoluteIri,
      rev: Long,
      deprecated: Boolean,
      default: Boolean,
      algorithm: String,
      bucket: String,
      settings: S3Settings,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends Storage {
    def reference: StorageReference = S3StorageReference(id, rev)
    def encrypt(implicit config: StorageConfig): Storage =
      copy(settings = settings.copy(credentials = settings.credentials.map {
        case S3Credentials(ak, as) => S3Credentials(ak.encrypt, as.encrypt)
      }))

    def decrypt(implicit config: StorageConfig): Storage =
      copy(settings = settings.copy(credentials = settings.credentials.map {
        case S3Credentials(ak, as) => S3Credentials(ak.decrypt, as.decrypt)
      }))

  }

  private implicit val permissionEncoder: NodeEncoder[Permission] = node =>
    stringEncoder(node).flatMap { perm =>
      Permission(perm).toRight(IllegalConversion(s"Invalid Permission '$perm'"))
    }

  /**
    * S3 connection settings with reasonable defaults.
    *
    * @param credentials optional credentials
    * @param endpoint    optional endpoint, either a domain or a full URL
    * @param region      optional region
    */
  final case class S3Settings(credentials: Option[S3Credentials], endpoint: Option[String], region: Option[String]) {
    val address: Uri = endpoint match {
      case None => "https://s3.amazonaws.com"
      case Some(s) =>
        if (s.startsWith("https://") || s.startsWith("http://")) s
        else s"https://$s"
    }

    /**
      * @return these settings converted to an instance of [[akka.stream.alpakka.s3.S3Settings]]
      */
    def toAlpakka: s3.S3Settings = {
      val credsProvider = credentials match {
        case Some(S3Credentials(accessKey, secretKey)) =>
          new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey))
        case None => new AWSStaticCredentialsProvider(new AnonymousAWSCredentials)
      }

      val regionProvider: AwsRegionProvider = new AwsRegionProvider {
        val getRegion: String = region.getOrElse {
          endpoint match {
            case None                                   => "us-east-1"
            case Some(s) if s.contains("amazonaws.com") => "us-east-1"
            case _                                      => "no-region"
          }
        }
      }

      s3.S3Settings(MemoryBufferType, credsProvider, regionProvider, ApiVersion.ListBucketVersion2)
        .withPathStyleAccess(true)
        .withEndpointUrl(address.toString())
    }
  }

  object S3Settings {

    /**
      * Attempts to select the system proxy for a given target URI.
      *
      * @param target a valid target URI
      * @return Some instance of [[akka.stream.alpakka.s3.Proxy]] if a proxy was found
      */
    private[storage] def getSystemProxy(target: String): Option[s3.Proxy] = {
      System.setProperty("java.net.useSystemProxies", "true")
      Try(new URI(target)).toOption
        .flatMap { uri =>
          val selector = ProxySelector.getDefault.select(uri)
          if (selector.isEmpty) None
          else selector.asScala.find(_.`type` == Type.HTTP).map(_.address)
        }
        .flatMap {
          case isa: InetSocketAddress => Some(s3.Proxy(isa.getHostString, isa.getPort, Uri.httpScheme(false)))
          case _                      => None
        }
    }
  }

  /**
    * S3 credentials.
    *
    * @param accessKey the AWS access key ID
    * @param secretKey the AWS secret key
    */
  final case class S3Credentials(accessKey: String, secretKey: String)

  /**
    * Attempts to transform the resource into a [[Storage]].
    *
    * @param res     a materialized resource
    * @return Right(storage) if the resource is compatible with a Storage, Left(rejection) otherwise
    */
  final def apply(res: ResourceV)(implicit config: StorageConfig): Either[Rejection, Storage] =
    if (Set(nxv.Storage.value, nxv.DiskStorage.value).subsetOf(res.types)) diskStorage(res)
    else if (Set(nxv.Storage.value, nxv.RemoteDiskStorage.value).subsetOf(res.types))
      remoteDiskStorage(res)
    else if (Set(nxv.Storage.value, nxv.S3Storage.value).subsetOf(res.types)) s3Storage(res)
    else Left(InvalidResourceFormat(res.id.ref, "The provided @type do not match any of the storage types"))

  private def diskStorage(res: ResourceV)(implicit config: StorageConfig): Either[Rejection, DiskStorage] = {
    val c = res.value.graph.cursor()
    // format: off
    for {
      default   <- c.downField(nxv.default).focus.as[Boolean].onError(res.id.ref, nxv.default.prefix)
      volume    <- c.downField(nxv.volume).focus.as[String].map(Paths.get(_)).onError(res.id.ref, nxv.volume.prefix)
      read      <- c.downField(nxv.readPermission).focus.as[Permission].orElse(config.disk.readPermission).onError(res.id.ref, nxv.readPermission.prefix)
      write     <- c.downField(nxv.writePermission).focus.as[Permission].orElse(config.disk.writePermission).onError(res.id.ref, nxv.writePermission.prefix)
      fileSize  <- c.downField(nxv.maxFileSize).focus.as[Long].orElse(config.disk.maxFileSize).onError(res.id.ref, nxv.maxFileSize.prefix)
    } yield DiskStorage(res.id.parent, res.id.value, res.rev, res.deprecated, default, config.disk.digestAlgorithm, volume, read, write, fileSize)
    // format: on
  }

  private def remoteDiskStorage(
      res: ResourceV
  )(implicit config: StorageConfig): Either[Rejection, RemoteDiskStorage] = {
    val c = res.value.graph.cursor()

    // format: off
    for {
      default       <- c.downField(nxv.default).focus.as[Boolean].onError(res.id.ref, nxv.default.prefix)
      endpoint      <- c.downField(nxv.endpoint).focus.as[Uri].orElse(config.remoteDisk.endpoint).onError(res.id.ref, nxv.endpoint.prefix)
      credentials   <- if(endpoint == config.remoteDisk.endpoint) c.downField(nxv.credentials).focus.asOption[String].map(_ orElse config.remoteDisk.defaultCredentials.map(_.value)).onError(res.id.ref, nxv.credentials.prefix)
                       else c.downField(nxv.credentials).focus.asOption[String].onError(res.id.ref, nxv.credentials.prefix)
      folder        <- c.downField(nxv.folder).focus.as[String].onError(res.id.ref, nxv.folder.prefix)
      read          <- c.downField(nxv.readPermission).focus.as[Permission].orElse(config.remoteDisk.readPermission).onError(res.id.ref, nxv.readPermission.prefix)
      write         <- c.downField(nxv.writePermission).focus.as[Permission].orElse(config.remoteDisk.writePermission).onError(res.id.ref, nxv.writePermission.prefix)
      fileSize      <- c.downField(nxv.maxFileSize).focus.as[Long].orElse(config.remoteDisk.maxFileSize).onError(res.id.ref, nxv.maxFileSize.prefix)
    } yield RemoteDiskStorage(res.id.parent, res.id.value, res.rev, res.deprecated, default, config.remoteDisk.digestAlgorithm, endpoint, credentials, folder, read, write, fileSize)
    // format: on
  }

  private def s3Storage(res: ResourceV)(implicit config: StorageConfig): Either[Rejection, S3Storage] = {
    val c = res.value.graph.cursor()
    // format: off
    for {
      default   <- c.downField(nxv.default).focus.as[Boolean].onError(res.id.ref, nxv.default.prefix)
      bucket    <- c.downField(nxv.bucket).focus.as[String].onError(res.id.ref, nxv.bucket.prefix)
      endpoint  <- c.downField(nxv.endpoint).focus.asOption[String].onError(res.id.ref, nxv.endpoint.prefix)
      region    <- c.downField(nxv.region).focus.asOption[String].onError(res.id.ref, nxv.region.prefix)
      read      <- c.downField(nxv.readPermission).focus.as[Permission].orElse(config.amazon.readPermission).onError(res.id.ref, nxv.readPermission.prefix)
      write     <- c.downField(nxv.writePermission).focus.as[Permission].orElse(config.amazon.writePermission).onError(res.id.ref, nxv.writePermission.prefix)
      fileSize  <- c.downField(nxv.maxFileSize).focus.as[Long].orElse(config.amazon.maxFileSize).onError(res.id.ref, nxv.maxFileSize.prefix)
      akOpt     <- c.downField(nxv.accessKey).focus.asOption[String].onError(res.id.ref, nxv.accessKey.prefix)
      skOpt     <- c.downField(nxv.secretKey).focus.asOption[String].onError(res.id.ref, nxv.secretKey.prefix)
    } yield S3Storage(res.id.parent, res.id.value, res.rev, res.deprecated, default, config.amazon.digestAlgorithm, bucket, S3Settings((akOpt, skOpt).mapN(S3Credentials), endpoint, region), read, write, fileSize)
    // format: on
  }

  trait FetchFile[F[_], Out] {

    /**
      * Fetches the file associated to the provided ''fileMeta''.
      *
      * @param fileMeta the file metadata
      */
    def apply(fileMeta: FileAttributes): F[Out]
  }

  trait SaveFile[F[_], In] {

    /**
      * Stores the provided stream source.
      *
      * @param id       the id of the resource
      * @param fileDesc the file descriptor to be stored
      * @param source   the source
      * @return [[FileAttributes]] wrapped in the abstract ''F[_]'' type if successful,
      *         or a [[ch.epfl.bluebrain.nexus.kg.resources.Rejection]] wrapped within ''F[_]'' otherwise
      */
    def apply(id: ResId, fileDesc: FileDescription, source: In): F[FileAttributes]
  }

  trait LinkFile[F[_]] {

    /**
      * Links an existing file to a storage.
      *
      * @param id       the id of the resource
      * @param fileDesc the file descriptor to be stored
      * @param path     the relative (to the storage) path of the file to be linked
      * @return [[FileAttributes]] wrapped in the abstract ''F[_]'' type if successful,
      *         or a [[ch.epfl.bluebrain.nexus.kg.resources.Rejection]] wrapped within ''F[_]'' otherwise
      */
    def apply(id: ResId, fileDesc: FileDescription, path: Uri.Path): F[FileAttributes]
  }

  trait VerifyStorage[F[_]] {

    /**
      * Verifies a storage
      */
    def apply: F[Either[String, Unit]]
  }

  trait FetchFileAttributes[F[_]] {

    /**
      * Fetches the file attributes associated to the provided ''relativePath''.
      *
      * @param relativePath the file relative path
      */
    def apply(relativePath: Uri.Path): F[StorageFileAttributes]

  }

  // $COVERAGE-OFF$
  object StorageOperations {

    /**
      * Verifies that the provided storage can be used
      *
      * @tparam F the effect type
      */
    trait Verify[F[_]] {
      def apply(storage: Storage): VerifyStorage[F]
    }

    object Verify {
      implicit final def apply[F[_]: Effect](implicit as: ActorSystem): Verify[F] = {
        case s: DiskStorage       => new DiskStorageOperations.VerifyDiskStorage[F](s)
        case s: RemoteDiskStorage => new RemoteDiskStorageOperations.Verify(s, s.client)
        case s: S3Storage         => new S3StorageOperations.Verify[F](s)
      }
    }

    /**
      * Provides a selected storage with [[FetchFile]] operation
      *
      * @tparam F   the effect type
      * @tparam Out the output type
      */
    trait Fetch[F[_], Out] {
      def apply(storage: Storage): FetchFile[F, Out]
    }

    object Fetch {
      implicit final def apply[F[_]: Effect](implicit as: ActorSystem): Fetch[F, AkkaSource] = {
        case _: DiskStorage       => new DiskStorageOperations.FetchDiskFile[F]
        case s: RemoteDiskStorage => new RemoteDiskStorageOperations.Fetch(s, s.client)
        case s: S3Storage         => new S3StorageOperations.Fetch(s)
      }
    }

    /**
      * Provides a selected storage with [[SaveFile]] operation
      *
      * @tparam F   the effect type
      * @tparam In  the input type
      */
    trait Save[F[_], In] {
      def apply(storage: Storage): SaveFile[F, In]
    }

    object Save {
      implicit final def apply[F[_]: Effect](implicit as: ActorSystem): Save[F, AkkaSource] = {
        case s: DiskStorage       => new DiskStorageOperations.SaveDiskFile(s)
        case s: RemoteDiskStorage => new RemoteDiskStorageOperations.Save(s, s.client)
        case s: S3Storage         => new S3StorageOperations.Save(s)
      }
    }

    /**
      * Provides a selected storage with [[LinkFile]] operation
      *
      * @tparam F   the effect type
      */
    trait Link[F[_]] {
      def apply(storage: Storage): LinkFile[F]
    }

    object Link {
      implicit final def apply[F[_]: Effect](implicit as: ActorSystem): Link[F] = {
        case _: DiskStorage       => new DiskStorageOperations.LinkDiskFile()
        case s: RemoteDiskStorage => new RemoteDiskStorageOperations.Link(s, s.client)
        case s: S3Storage         => new S3StorageOperations.Link(s)
      }
    }

    /**
      * Provides a selected storage with [[FetchFileAttributes]] operation
      *
      * @tparam F   the effect type
      */
    trait FetchAttributes[F[_]] {
      def apply(storage: Storage): FetchFileAttributes[F]
    }

    object FetchAttributes {
      implicit final def apply[F[_]: Effect](implicit as: ActorSystem): FetchAttributes[F] = {
        case _: DiskStorage       => new DiskStorageOperations.FetchAttributes()
        case _: S3Storage         => new S3StorageOperations.FetchAttributes()
        case s: RemoteDiskStorage => new RemoteDiskStorageOperations.FetchAttributes(s, s.client)
      }
    }
  }
  // $COVERAGE-ON$
}
