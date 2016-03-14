package controllers

import db.DashboardBuildsDao
import io.flow.delta.v0.models.json._
import play.api.mvc._
import play.api.libs.json._

@javax.inject.Singleton
class DashboardBuilds @javax.inject.Inject() (
  val tokenClient: io.flow.token.v0.interfaces.Client
) extends Controller with BaseIdentifiedRestController {

  def get(
    limit: Long,
    offset: Long
  ) = Identified { request =>
    Ok(
      Json.toJson(
        DashboardBuildsDao.findAll(
          authorization(request),
          limit = limit,
          offset = offset
        )
      )
    )
  }

}
