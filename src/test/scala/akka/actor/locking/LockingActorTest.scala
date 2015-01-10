package us.bleibinha.akka.actor.locking

import scala.annotation.tailrec
import scala.concurrent.duration._
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
      expectMsg("OK")
    }

    "process LockAwareMessages in the same order as they are coming in" in {
      val action1 = () ⇒ Future { Thread.sleep(100); self ! "1" }
      defaultLockingActor ! LockAwareMessage(1, action1)
      val action2 = () ⇒ Future { Thread.sleep(100); self ! "2" }
      defaultLockingActor ! LockAwareMessage(1, action2)
      val action3 = () ⇒ Future { Thread.sleep(100); self ! "3" }
      defaultLockingActor ! LockAwareMessage(1, action3)
      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
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

    "release lock after expirationTime" in {
      val blockingAction = () ⇒ Future { Thread.sleep(30000) }
      defaultLockingActor ! LockAwareMessage(1, blockingAction, 100.millis)
      val action = () ⇒ Future { self ! "OK" }
      defaultLockingActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "release lock after default expiration Time" in {
      val lockingActorWithDefaultExp = LockingActor(100.millis)(system)
      val blockingAction = () ⇒ Future { Thread.sleep(30000) }
      lockingActorWithDefaultExp ! LockAwareMessage(1, blockingAction)
      val action = () ⇒ Future { self ! "OK" }
      lockingActorWithDefaultExp ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "release lock after expiration Time (and not after default expiration Time)" in {
      val lockingActorWithDefaultExp = LockingActor(30.seconds)(system)
      val blockingAction = () ⇒ Future { Thread.sleep(30000) }
      lockingActorWithDefaultExp ! LockAwareMessage(1, blockingAction, 100.millis)
      val action = () ⇒ Future { self ! "OK" }
      lockingActorWithDefaultExp ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "release lock even when action fails" in {
      val failingAction = () ⇒ Future { throw new Exception("Oh noes!") }
      defaultLockingActor ! LockAwareMessage(1, failingAction)
      val action = () ⇒ Future { self ! "OK" }
      defaultLockingActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

  }

  var defaultLockingActor: ActorRef = _

  override def beforeEach {
    defaultLockingActor = LockingActor()(system)
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

  def nowLong() = System.currentTimeMillis

  case class Timespan(lockObj: Any, time1: Long, time2: Long)
  object Timespan {
    @tailrec
    def isIntersecting(durations: List[Timespan]): Boolean = durations match {
      case duration1 :: rest ⇒
        val intersecting =
          rest.filterNot(
            duration2 ⇒ duration1.time1 >= duration2.time2 || duration1.time2 <= duration2.time1
          )
        if (intersecting.isEmpty) isIntersecting(rest) else true
      case Nil ⇒ false
    }
  }
}