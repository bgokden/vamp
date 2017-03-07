package io.vamp.operation.config

import akka.pattern.ask
import io.vamp.common.{ Config, ConfigFilter }
import io.vamp.common.akka._
import io.vamp.operation.notification._
import io.vamp.persistence.KeyValueStoreActor

import scala.concurrent.Future
import scala.util.Try

object ConfigurationLoaderActor {

  val timeout = KeyValueStoreActor.timeout

  object Reload

  case class Get(`type`: String, flatten: Boolean, filter: ConfigFilter)

  case class Set(input: String, filter: ConfigFilter, validateOnly: Boolean)

}

class ConfigurationLoaderActor extends CommonSupportForActors with OperationNotificationProvider {

  import ConfigurationLoaderActor._

  private implicit val timeout = ConfigurationLoaderActor.timeout()

  def receive = {
    case Reload       ⇒ reload()
    case request: Get ⇒ reply(get(request.`type`, request.flatten, request.filter))
    case request: Set ⇒ reply(set(request.input, request.filter, request.validateOnly))
    case _            ⇒
  }

  override def preStart() = {
    if (Config.boolean("vamp.operation.reload-configuration")()) {
      val delay = Config.duration("vamp.operation.reload-configuration-delay")()
      log.info("Getting configuration update from key-value store")
      context.system.scheduler.scheduleOnce(delay, self, Reload)
    }
  }

  private def get(`type`: String, flatten: Boolean, filter: ConfigFilter) = Future.successful {
    Config.export(
      Try(Config.Type.withName(`type`)).getOrElse(Config.Type.applied),
      flatten, filter
    )
  }

  private def set(input: String, filter: ConfigFilter, validateOnly: Boolean): Future[_] = try {
    val config = if (input.trim.isEmpty) Map[String, Any]() else Config.unmarshall(input.trim, filter)
    if (validateOnly) Future.successful(config)
    else IoC.actorFor[KeyValueStoreActor] ? KeyValueStoreActor.Set("configuration" :: Nil, if (config.isEmpty) None else Option(Config.marshall(config))) map { _ ⇒
      reload()
    }
  }
  catch {
    case _: Exception ⇒ throwException(InvalidConfigurationError)
  }

  private def reload() = {
    def reload(config: Map[String, Any]) = {
      log.info("Reloading due to configuration change")
      Config.load(config)
      actorSystem.actorSelection(s"/user/${namespace.name}-config") ! "reload"
    }

    IoC.actorFor[KeyValueStoreActor] ? KeyValueStoreActor.Get("configuration" :: Nil) map {
      case Some(content: String) if content != Config.marshall(Config.export(Config.Type.dynamic, flatten = false)) ⇒ reload(Config.unmarshall(content))
      case None if Config.export(Config.Type.dynamic).nonEmpty ⇒ reload(Map())
      case _ ⇒
    }
  }
}
