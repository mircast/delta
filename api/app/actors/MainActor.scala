package io.flow.delta.actors

import db.{BuildsDao, ProjectsDao}
import io.flow.delta.api.lib.StateDiff
import io.flow.play.actors.{ErrorHandler, Scheduler}
import io.flow.postgresql.{Authorization, Pager}
import play.api.libs.concurrent.Akka
import akka.actor._
import play.api.{Application, Environment, Logger, Mode}
import play.api.Play.current
import play.api.libs.concurrent.InjectedActorSupport

object MainActor {

  lazy val SystemUser = db.UsersDao.systemUser

  object Messages {

    case class BuildDockerImage(buildId: String, version: String)
    case class CheckLastState(buildId: String)

    case class ProjectCreated(id: String)
    case class ProjectUpdated(id: String)
    case class ProjectDeleted(id: String)
    case class ProjectSync(id: String)

    case class BuildCreated(id: String)
    case class BuildUpdated(id: String)
    case class BuildDeleted(id: String)
    case class BuildSync(id: String)
    case class BuildCheckTag(id: String, name: String)

    case class BuildDesiredStateUpdated(buildId: String)
    case class BuildLastStateUpdated(buildId: String)

    case class Scale(buildId: String, diffs: Seq[StateDiff])

    case class ShaUpserted(projectId: String, id: String)

    case class TagCreated(projectId: String, id: String, name: String)
    case class TagUpdated(projectId: String, id: String, name: String)

    case class UserCreated(id: String)

    case class ImageCreated(buildId: String, id: String, version: String)

    case class ConfigureAWS(buildId: String)

    case class EnsureContainerAgentHealth(buildId: String)
    case class UpdateContainerAgent(buildId: String)
    case class RemoveOldServices(buildId: String)
  }
}

@javax.inject.Singleton
class MainActor @javax.inject.Inject() (
  buildFactory: BuildActor.Factory,
  dockerHubFactory: DockerHubActor.Factory,
  dockerHubTokenFactory: DockerHubTokenActor.Factory,
  projectFactory: ProjectActor.Factory,
  override val config: io.flow.play.util.Config,
  system: ActorSystem,
  playEnv: Environment
) extends Actor with ActorLogging with ErrorHandler with Scheduler with InjectedActorSupport{

  private[this] implicit val ec = system.dispatchers.lookup("main-actor-context")

  private[this] val name = "main"

  private[this] val searchActor = system.actorOf(Props[SearchActor], name = s"$name:SearchActor")
  private[this] val dockerHubTokenActor = injectedChild(dockerHubTokenFactory(), name = s"main:DockerHubActor")

  private[this] val buildActors = scala.collection.mutable.Map[String, ActorRef]()
  private[this] val buildSupervisorActors = scala.collection.mutable.Map[String, ActorRef]()
  private[this] val dockerHubActors = scala.collection.mutable.Map[String, ActorRef]()
  private[this] val projectActors = scala.collection.mutable.Map[String, ActorRef]()
  private[this] val projectSupervisorActors = scala.collection.mutable.Map[String, ActorRef]()
  private[this] val userActors = scala.collection.mutable.Map[String, ActorRef]()

  playEnv.mode match {
    case Mode.Test => {
      Logger.info("[MainActor] Background actors are disabled in Test")
    }

    case _ => {
      scheduleRecurring(system, "main.actor.update.jwt.token.seconds") {
        dockerHubTokenActor ! DockerHubTokenActor.Messages.Refresh
      }

      scheduleRecurring(system, "main.actor.ensure.container.agent.health.seconds") {
        Pager.create { offset =>
          BuildsDao.findAll(Authorization.All, offset = offset)
        }.foreach { build =>
          self ! MainActor.Messages.EnsureContainerAgentHealth(build.id)
        }
      }

      scheduleRecurring(system, "main.actor.update.container.agent.seconds") {
        Pager.create { offset =>
          BuildsDao.findAll(Authorization.All, offset = offset)
        }.foreach { build =>
          self ! MainActor.Messages.UpdateContainerAgent(build.id)
        }
      }

      scheduleRecurring(system, "main.actor.remove.old.services.seconds") {
        Pager.create { offset =>
          BuildsDao.findAll(Authorization.All, offset = offset)
        }.foreach { build =>
          self ! MainActor.Messages.RemoveOldServices(build.id)
        }
      }

      scheduleRecurring(system, "main.actor.project.sync.seconds") {
        Pager.create { offset =>
          ProjectsDao.findAll(Authorization.All, offset = offset)
        }.foreach { project =>
          self ! MainActor.Messages.ProjectSync(project.id)
        }
      }

      scheduleRecurring(system, "main.actor.project.inactive.check.seconds") {
        Pager.create { offset =>
          ProjectsDao.findAll(Authorization.All, offset = offset, minutesSinceLastEvent = Some(15))
        }.foreach { project =>
          Logger.info(s"Sending ProjectSync(${project.id}) - no events found in last 15 minutes")
          self ! MainActor.Messages.ProjectSync(project.id)
        }
      }
    }
  }

  def receive = playEnv.mode match {
    case Mode.Test => {
      case msg => {
        Logger.info(s"[MainActor TEST] Discarding received message: $msg")
      }
    }

    case _ => akka.event.LoggingReceive {
      case msg @ MainActor.Messages.BuildCreated(id) => withErrorHandler(msg) {
        upsertBuildSupervisorActor(id) ! BuildSupervisorActor.Messages.PursueDesiredState
      }

      case msg @ MainActor.Messages.BuildUpdated(id) => withErrorHandler(msg) {
        upsertBuildSupervisorActor(id) ! BuildSupervisorActor.Messages.PursueDesiredState
      }

      case msg @ MainActor.Messages.BuildDeleted(id) => withErrorHandler(msg) {
        (buildActors -= id).map { case (id, actor) =>
          actor ! BuildActor.Messages.Delete
          actor ! PoisonPill
        }
      }

      case msg @ MainActor.Messages.BuildSync(id) => withErrorHandler(msg) {
        upsertBuildSupervisorActor(id) ! BuildSupervisorActor.Messages.PursueDesiredState
      }

      case msg @ MainActor.Messages.BuildCheckTag(id, name) => withErrorHandler(msg) {
        upsertBuildSupervisorActor(id) ! BuildSupervisorActor.Messages.CheckTag(name)
      }

      case msg @ MainActor.Messages.UserCreated(id) => withErrorHandler(msg) {
        upsertUserActor(id) ! UserActor.Messages.Created
      }

      case msg @ MainActor.Messages.ProjectCreated(id) => withErrorHandler(msg) {
        self ! MainActor.Messages.ProjectSync(id)
      }

      case msg @ MainActor.Messages.ProjectUpdated(id) => withErrorHandler(msg) {
        searchActor ! SearchActor.Messages.SyncProject(id)
        upsertProjectSupervisorActor(id) ! ProjectSupervisorActor.Messages.PursueDesiredState
      }

      case msg @ MainActor.Messages.ProjectDeleted(id) => withErrorHandler(msg) {
        searchActor ! SearchActor.Messages.SyncProject(id)

        (projectActors -= id).map { case (id, actor) =>
          actor ! PoisonPill
        }

        (projectSupervisorActors -= id).map { case (id, actor) =>
          actor ! PoisonPill
        }
      }

      case msg @ MainActor.Messages.ProjectSync(id) => withErrorHandler(msg) {
        val ref = upsertProjectActor(id)
        ref ! ProjectActor.Messages.SyncConfig
        ref ! ProjectActor.Messages.SyncBuilds
        upsertProjectSupervisorActor(id) ! ProjectSupervisorActor.Messages.PursueDesiredState
        searchActor ! SearchActor.Messages.SyncProject(id)
      }

      case msg @ MainActor.Messages.Scale(buildId, diffs) => withErrorHandler(msg) {
        upsertBuildActor(buildId) ! BuildActor.Messages.Scale(diffs)
      }

      case msg @ MainActor.Messages.ShaUpserted(projectId, id) => withErrorHandler(msg) {
        upsertProjectSupervisorActor(projectId) ! ProjectSupervisorActor.Messages.PursueDesiredState
      }

      case msg @ MainActor.Messages.TagCreated(projectId, id, name) => withErrorHandler(msg) {
        upsertProjectSupervisorActor(projectId) ! ProjectSupervisorActor.Messages.CheckTag(name)
      }

      case msg @ MainActor.Messages.TagUpdated(projectId, id, name) => withErrorHandler(msg) {
        upsertProjectSupervisorActor(projectId) ! ProjectSupervisorActor.Messages.CheckTag(name)
      }

      case msg @ MainActor.Messages.ImageCreated(buildId, id, version) => withErrorHandler(msg) {
        upsertBuildSupervisorActor(buildId) ! BuildSupervisorActor.Messages.CheckTag(version)
      }

      case msg @ MainActor.Messages.BuildDockerImage(buildId, version) => withErrorHandler(msg) {
        upsertDockerHubActor(buildId) ! DockerHubActor.Messages.Build(version)
      }

      case msg @ MainActor.Messages.CheckLastState(buildId) => withErrorHandler(msg) {
        upsertBuildActor(buildId) ! BuildActor.Messages.CheckLastState
      }

      case msg @ MainActor.Messages.BuildDesiredStateUpdated(buildId) => withErrorHandler(msg) {
        upsertBuildSupervisorActor(buildId) ! BuildSupervisorActor.Messages.PursueDesiredState
      }

      case msg @ MainActor.Messages.BuildLastStateUpdated(buildId) => withErrorHandler(msg) {
        upsertBuildSupervisorActor(buildId) ! BuildSupervisorActor.Messages.PursueDesiredState
      }

      case msg @ MainActor.Messages.ConfigureAWS(buildId) => withErrorHandler(msg) {
        upsertBuildActor(buildId) ! BuildActor.Messages.ConfigureAWS
      }

      case msg @ MainActor.Messages.RemoveOldServices(buildId) => withErrorHandler(msg) {
        upsertBuildActor(buildId) ! BuildActor.Messages.RemoveOldServices
      }

      case msg @ MainActor.Messages.UpdateContainerAgent(buildId) => withErrorHandler(msg) {
        upsertBuildActor(buildId) ! BuildActor.Messages.UpdateContainerAgent
      }

      case msg @ MainActor.Messages.EnsureContainerAgentHealth(buildId) => withErrorHandler(msg) {
        upsertBuildActor(buildId) ! BuildActor.Messages.EnsureContainerAgentHealth
      }

      case msg: Any => logUnhandledMessage(msg)

    }
  }

  def upsertDockerHubActor(buildId: String): ActorRef = {
    dockerHubActors.lift(buildId).getOrElse {
      val ref = injectedChild(dockerHubFactory(buildId), name = s"$name:dockerHubActor:$buildId")
      ref ! DockerHubActor.Messages.Setup
      dockerHubActors += (buildId -> ref)
      ref
    }
  }

  def upsertUserActor(id: String): ActorRef = {
    userActors.lift(id).getOrElse {
      val ref = system.actorOf(Props[UserActor], name = s"$name:userActor:$id")
      ref ! UserActor.Messages.Data(id)
      userActors += (id -> ref)
      ref
    }
  }

  def upsertProjectActor(id: String): ActorRef = {
    projectActors.lift(id).getOrElse {
      val ref = injectedChild(projectFactory(id), name = s"$name:projectActor:$id")
      ref ! ProjectActor.Messages.Setup
      projectActors += (id -> ref)
      ref
    }
  }

  def upsertBuildActor(id: String): ActorRef = {
    buildActors.lift(id).getOrElse {
      val ref = injectedChild(buildFactory(id), name = s"$name:buildActor:$id")
      ref ! BuildActor.Messages.Setup
      buildActors += (id -> ref)
      ref
    }
  }

  def upsertProjectSupervisorActor(id: String): ActorRef = {
    projectSupervisorActors.lift(id).getOrElse {
      val ref = system.actorOf(Props[ProjectSupervisorActor], name = s"$name:projectSupervisorActor:$id")
      ref ! ProjectSupervisorActor.Messages.Data(id)
      projectSupervisorActors += (id -> ref)
      ref
    }
  }

  def upsertBuildSupervisorActor(id: String): ActorRef = {
    buildSupervisorActors.lift(id).getOrElse {
      val ref = system.actorOf(Props[BuildSupervisorActor], name = s"$name:buildSupervisorActor:$id")
      ref ! BuildSupervisorActor.Messages.Data(id)
      buildSupervisorActors += (id -> ref)
      ref
    }
  }
}
