package com.lookout.borderpatrol.test

import java.net.URL

import com.lookout.borderpatrol.HealthCheck.UrlHealthCheck
import com.lookout.borderpatrol._
import com.twitter.finagle.Service
import com.twitter.finagle.http.path.Path
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.http.{Response, Request, Status}
import com.twitter.util.{Future, Await}
import io.circe.generic.auto._
import io.circe.syntax._


class HealthCheckSpec extends BorderPatrolSuite {

  val healthyCheck1 = new HealthCheck() {
    val name: String = "healthyCheck1"

    def check(): Future[HealthCheckStatus] = Future.value(HealthCheckStatus.healthy)
  }
  val healthyCheck2 = new HealthCheck() {
    val name: String = "healthyCheck2"

    def check(): Future[HealthCheckStatus] =
      Future.value(HealthCheckStatus.healthy("healthy in string format"))
  }
  val healthyCheck3 = new HealthCheck() {
    val name: String = "healthyCheck3"

    def check(): Future[HealthCheckStatus] =
      Future.value(HealthCheckStatus.healthy( """{"description":"healthy in JSON format"}"""))
  }
  val unhealthyCheck1 = new HealthCheck() {
    val name: String = "unhealthyCheck1"

    def check(): Future[HealthCheckStatus] =
      Future.exception(new Exception("unhealthy in exception"))
  }
  val unhealthyCheck2 = new HealthCheck() {
    val name: String = "unhealthyCheck2"

    def check(): Future[HealthCheckStatus] =
      Future.value(HealthCheckStatus.unhealthy(Status.NotAcceptable, "unhealthy in string format"))
  }
  val unhealthyCheck3 = new HealthCheck() {
    val name: String = "unhealthyCheck3"

    def check(): Future[HealthCheckStatus] =
      Future.value(HealthCheckStatus.unhealthy(Status.NotImplemented, """{"description":"unhealthy in JSON format"}"""))
  }

  override def afterEach(): Unit = {
    try {
      super.afterEach() // To be stackable, must call super.afterEach
    }
    finally {
      Endpoint.clearCache()
    }
  }

  behavior of "HealthCheckRegistry"

  it should "collect output from all HealthChecks" in {
    val registry = new HealthCheckRegistry()
    registry.register(healthyCheck1)
    registry.register(healthyCheck2)
    registry.register(healthyCheck3)
    registry.register(unhealthyCheck1)
    registry.register(unhealthyCheck2)
    registry.register(unhealthyCheck3)

    // Execute
    val output = registry.collectHealthCheckResults()

    // Verify
    Await.result(output).get("healthyCheck1").get.status should be(Status.Ok)
    Await.result(output).get("healthyCheck2").get.status should be(Status.Ok)
    Await.result(output).get("healthyCheck3").get.status should be(Status.Ok)
    Await.result(output).get("unhealthyCheck1").get.status should be(Status.InternalServerError)
    Await.result(output).get("unhealthyCheck2").get.status should be(Status.NotAcceptable)
    Await.result(output).get("unhealthyCheck3").get.status should be(Status.NotImplemented)
    val jsonOutput = Await.result(output).asJson.toString()
    jsonOutput should include("healthy in string format")
    jsonOutput should include("healthy in JSON format")
    jsonOutput should include("unhealthy in exception")
    jsonOutput should include("unhealthy in string format")
    jsonOutput should include("unhealthy in JSON format")
  }

  behavior of "UrlHealthCheck"

  it should "collect output from all HealthChecks" in {
    val server = com.twitter.finagle.Http.serve(
      "localhost:5679",
      RoutingService.byPath {
        case p1 if p1 contains "good" => Service.mk[Request, Response] { req =>
          Response(Status.Ok).toFuture
        }
        case _ => Service.mk[Request, Response] { _ => Response(Status.NotAcceptable).toFuture}
      }
    )
    try {
      val goodUrlHealthCheck = UrlHealthCheck("goodUrlHealthCheck",
        SimpleEndpoint("good", Path("/good"), Set(new URL("http://localhost:5679"))))
      val badUrlHealthCheck = UrlHealthCheck("badUrlHealthCheck",
        SimpleEndpoint("bad", Path("/bad"), Set(new URL("http://localhost:5679"))))
      val uglyUrlHealthCheck = UrlHealthCheck("uglyUrlHealthCheck",
        SimpleEndpoint("ugly", Path("ugly"), Set(new URL("http://localhost:9999"))))
      val registry = new HealthCheckRegistry()
      registry.register(goodUrlHealthCheck)
      registry.register(badUrlHealthCheck)
      registry.register(uglyUrlHealthCheck)

      // Execute
      val output = registry.collectHealthCheckResults()

      // Verify
      Await.result(output).get("goodUrlHealthCheck").get.status should be(Status.Ok)
      Await.result(output).get("badUrlHealthCheck").get.status should be(Status.NotAcceptable)
      Await.result(output).get("uglyUrlHealthCheck").get.status should be(Status.InternalServerError)
      Await.result(output).asJson.toString() should include(
        "An error occurred while talking to: Failed to connect for: 'ugly'")

    } finally {
      server.close()
    }
  }
}
