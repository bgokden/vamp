package io.vamp.persistence

import java.io.{ File, FileWriter }

import akka.actor.Actor
import io.vamp.common.{ Artifact, ClassMapper, Config, ConfigMagnet }
import io.vamp.persistence.notification.CorruptedDataException

import scala.concurrent.Future
import scala.io.Source

class FilePersistenceActorMapper extends ClassMapper {
  val name = "file"
  val clazz: Class[_] = classOf[FilePersistenceActor]
}

object FilePersistenceActor {
  val directory: ConfigMagnet[String] = Config.string("vamp.persistence.file.directory")
}

class FilePersistenceActor extends InMemoryRepresentationPersistenceActor with PersistenceMarshaller with PersistenceDataReader with AccessGuard {

  import FilePersistenceActor._

  private lazy val file = {
    val dir = directory()
    val file = new File(
      if (dir.endsWith(File.separator)) s"$dir${namespace.name}.db" else s"$dir${File.separator}${namespace.name}.db"
    )
    file.getParentFile.mkdirs()
    file.createNewFile()
    file
  }

  override def receive: Receive = ({
    case "load" ⇒ read()
  }: Actor.Receive) orElse super[InMemoryRepresentationPersistenceActor].receive

  override def preStart(): Unit = self ! "load"

  override protected def info(): Future[Map[String, Any]] = super.info().map(_ + ("type" → "file") + ("file" → file.getAbsolutePath))

  protected def read(): Unit = this.synchronized {
    try {
      for (line ← Source.fromFile(file).getLines()) {
        if (line.nonEmpty) readData(line)
      }
      removeGuard()
    }
    catch {
      case c: CorruptedDataException ⇒
        reportException(c)
        validData = false
    }
  }

  override protected def all(`type`: Class[_ <: Artifact], page: Int, perPage: Int, filter: (Artifact) ⇒ Boolean = (_) ⇒ true): Future[ArtifactResponseEnvelope] = {
    guard()
    super.all(`type`, page, perPage, filter)
  }

  override protected def get(name: String, `type`: Class[_ <: Artifact]): Future[Option[Artifact]] = {
    guard()
    super.get(name, `type`)
  }

  protected def set(artifact: Artifact): Future[Artifact] = Future.successful {
    write(PersistenceRecord(artifact.name, artifact.kind, marshall(artifact)))
    setArtifact(artifact)
  }

  protected def delete(name: String, `type`: Class[_ <: Artifact]): Future[Boolean] = Future.successful {
    write(PersistenceRecord(name, type2string(`type`)))
    deleteArtifact(name, type2string(`type`)).isDefined
  }

  private def write(record: PersistenceRecord): Unit = {
    guard()
    val writer = new FileWriter(file, true)
    this.synchronized {
      try {
        writer.write(s"${marshallRecord(record)}\n")
        writer.flush()
      }
      finally writer.close()
    }
  }
}
