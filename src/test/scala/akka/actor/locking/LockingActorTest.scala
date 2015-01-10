package us.bleibinha.akka.actor.locking

import scala.annotation.tailrec
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.testkit.TestProbe
import LockingActor._

class LockingActorTest extends BaseAkkaTest() {
  import system.dispatcher

  "DefaultLockingActor" must {

    "process a LockAwareMessage" in {
      val action = () ⇒ self ! "OK"
      defaultLockingActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "process a LockAwareMessage (with Future result)" in {
      val action = () ⇒ Future { self ! "OK" }
      defaultLockingActor ! LockAwareMessage(1, action)
      val msg = expectMsgType[String]
      msg should be("OK")
    }

    "process sequential LockAwareMessages" in {
      defaultLockingActor ! timespanLockMessage(1)
      defaultLockingActor ! timespanLockMessage(1)
      defaultLockingActor ! timespanLockMessage(1)
      defaultLockingActor ! timespanLockMessage(1)
      val timespan1 = expectMsgType[Timespan]
      val timespan2 = expectMsgType[Timespan]
      val timespan3 = expectMsgType[Timespan]
      val timespan4 = expectMsgType[Timespan]
      val timespans = List(timespan1, timespan2, timespan3, timespan4)
      Timespan.isIntersecting(timespans) should be(false)
    }

    "process sequential LockAwareMessages (two different lock objects)" in {
      defaultLockingActor ! timespanLockMessage(1)
      defaultLockingActor ! timespanLockMessage(1)
      defaultLockingActor ! timespanLockMessage(2)
      defaultLockingActor ! timespanLockMessage(2)
      val timespan1 = expectMsgType[Timespan]
      val timespan2 = expectMsgType[Timespan]
      val timespan3 = expectMsgType[Timespan]
      val timespan4 = expectMsgType[Timespan]
      val allTimespans = List(timespan1, timespan2, timespan3, timespan4)
      Timespan.isIntersecting(allTimespans) should be(true)
      val lockObj1Timespans = allTimespans.filter(_.lockObj == 1)
      Timespan.isIntersecting(lockObj1Timespans) should be(false)
      val lockObj2Timespans = allTimespans.filter(_.lockObj == 2)
      Timespan.isIntersecting(lockObj2Timespans) should be(false)
    }

  }

  def timespanLockMessage(lockObj: Any) = {
    val timespanLockAction =
      () ⇒
        Future {
          val time1 = nowLong()
          Thread.sleep(500)
          val time2 = nowLong()
          self ! Timespan(lockObj, time1, time2)
        }
    LockAwareMessage(lockObj, timespanLockAction)
  }

  var defaultLockingActor: ActorRef = _

  override def beforeEach {
    defaultLockingActor = LockingActor()(system)
  }

  def nowLong() = System.currentTimeMillis

  case class Timespan(lockObj: Any, time1: Long, time2: Long)
  object Timespan {
    @tailrec
    def isIntersecting(durations: List[Timespan]): Boolean = durations match {
      case duration1 :: rest ⇒
        val intersecting = rest.filterNot(duration2 ⇒ duration1.time1 >= duration2.time2 || duration1.time2 <= duration2.time1)
        if (intersecting.isEmpty) isIntersecting(rest) else true
      case Nil ⇒ false
    }
  }
}
