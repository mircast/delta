package io.flow.delta.actors

import io.flow.delta.v0.models.ProjectSummary
import io.flow.play.actors.Util
import io.flow.postgresql.Authorization
import db.{ItemForm, ItemsDao, ProjectsDao}
import play.api.Logger
import akka.actor.Actor

object SearchActor {

  sealed trait Message

  object Messages {
    case class SyncProject(id: String) extends Message
  }

}

class SearchActor extends Actor with Util {

  def receive = {

    case m @ SearchActor.Messages.SyncProject(id) => withVerboseErrorHandler(m) {
      ProjectsDao.findById(Authorization.All, id) match {
        case None => ItemsDao.deleteByObjectId(Authorization.All, MainActor.SystemUser, id)
        case Some(project) => ItemsDao.replaceProject(MainActor.SystemUser, project)
      }
    }

    case m: Any => logUnhandledMessage(m)
  }

}