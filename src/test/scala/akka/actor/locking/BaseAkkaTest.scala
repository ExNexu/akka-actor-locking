package us.bleibinha.akka.actor.locking

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Random

import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

abstract class BaseAkkaTest(system: ActorSystem)
  extends TestKit(system)
  with ImplicitSender
  with WordSpecLike
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with Matchers {

  def this() = this(ActorSystem("BaseAkkaTest-" + new Random().nextInt))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def await[T](fut: Future[T], duration: FiniteDuration = 3.seconds): T = Await.result(fut, duration)
}
