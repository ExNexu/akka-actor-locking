package us.bleibinha.akka.actor.locking

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import akka.testkit.TestProbe

class LockActorTest extends BaseAkkaTest() {
  import system.dispatcher

  implicit val timeout = Timeout(3 seconds)

  "DefaultLockActor" must {

    "process a LockAwareMessage" in {
      val action = () ⇒ self ! "OK"
      lockActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "process a LockAwareMessage (with Future result)" in {
      val action = () ⇒ Future { self ! "OK" }
      lockActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "process LockAwareMessages in the same order as they are coming in" in {
      val action1 = () ⇒ Future { Thread.sleep(100); self ! "1" }
      lockActor ! LockAwareMessage(1, action1)
      val action2 = () ⇒ Future { Thread.sleep(100); self ! "2" }
      lockActor ! LockAwareMessage(1, action2)
      val action3 = () ⇒ Future { Thread.sleep(100); self ! "3" }
      lockActor ! LockAwareMessage(1, action3)
      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
    }

    "process sequential LockAwareMessages" in {
      lockActor ! timespanLockMessage(1)
      lockActor ! timespanLockMessage(1)
      lockActor ! timespanLockMessage(1)
      lockActor ! timespanLockMessage(1)
      val timespan1 = expectMsgType[Timespan]
      val timespan2 = expectMsgType[Timespan]
      val timespan3 = expectMsgType[Timespan]
      val timespan4 = expectMsgType[Timespan]
      val timespans = List(timespan1, timespan2, timespan3, timespan4)
      Timespan.isIntersecting(timespans) should be(false)
    }

    "process sequential LockAwareMessages (two different lock objects)" in {
      lockActor ! timespanLockMessage(1)
      lockActor ! timespanLockMessage(1)
      lockActor ! timespanLockMessage(2)
      lockActor ! timespanLockMessage(2)
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

    "release lock even when action fails" in {
      val failingAction = () ⇒ { throw new Exception("Oh noes!") }
      lockActor ! LockAwareMessage(1, failingAction)
      val action = () ⇒ Future { self ! "OK" }
      lockActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "release lock even when action fails (with Future)" in {
      val failingAction = () ⇒ Future { throw new Exception("Oh noes!") }
      lockActor ! LockAwareMessage(1, failingAction)
      val action = () ⇒ Future { self ! "OK" }
      lockActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    "respond to ask request" in {
      val request = () ⇒ "OK"
      val result = await(lockActor.ask(LockAwareRequest(1, request)))
      result should be("OK")
    }

    "respond to ask request (with Future)" in {
      val request = () ⇒ Future { "OK" }
      val result = await(lockActor.ask(LockAwareRequest(1, request)))
      result should be("OK")
    }

    "respond to queued requests" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      val request1 = () ⇒ Future { Thread.sleep(100); "OK1" }
      val request2 = () ⇒ Future { Thread.sleep(100); "OK2" }
      probe1.send(lockActor, LockAwareRequest(1, request1))
      probe2.send(lockActor, LockAwareRequest(1, request2))
      probe1.expectMsg("OK1")
      probe2.expectMsg("OK2")
    }

    "release lock after unlock message has been sent" in {
      val blockingAction = () ⇒ Future { Thread.sleep(30000) }
      lockActor ! LockAwareMessage(1, blockingAction)
      lockActor ! Unlock(1)
      val action = () ⇒ Future { self ! "OK" }
      lockActor ! LockAwareMessage(1, action)
      expectMsg("OK")
    }

    // https://github.com/ExNexu/akka-actor-locking/issues/1#issuecomment-353286769
    "maintain order of messages" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      val ddosProbs: Array[TestProbe] = Array.fill[TestProbe](20)(TestProbe())

      val request1 = () ⇒ Future { Thread.sleep(1000); "OK1" }
      val request2 = () ⇒ Future { Thread.sleep(1000); "OK2" }

      probe1.send(lockActor, LockAwareRequest(1, request1))
      probe2.send(lockActor, LockAwareRequest(1, request2))
      Thread.sleep(700)

      ddosProbs.zipWithIndex.foreach {
        case (prob, index) ⇒
          Thread.sleep(1)
          val request = () ⇒ Future { Thread.sleep(1); "Extra:OK" + index}
          prob.send(lockActor, LockAwareRequest(1, request))
      }

      probe1.expectMsg("OK1")
      probe2.expectMsg("OK2")
      ddosProbs.zipWithIndex.foreach {
        case (prob, index) ⇒ prob.expectMsg("Extra:OK" + index)
      }
    }

  }

  var lockActor: ActorRef = _

  override def beforeEach {
    lockActor = LockActor()(system)
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
