package us.bleibinha.akka.actor.locking

import scala.collection.immutable.Queue
import scala.concurrent.Future

import akka.actor.{Actor, ActorRef, ActorSystem, Props}

object LockActor {

  def apply()(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new DefaultLockActor()))

  private[LockActor] class DefaultLockActor() extends LockActor {

    override def receive = lockAwareReceive
  }

  private[LockActor] case class LockAwareWithRequester(lockAwareRequest: LockAwareRequest, originalRequester: ActorRef)
    extends LockAwareRequest with LockAwareMessage {

    override def lockObj = lockAwareRequest.lockObj

    override def request = lockAwareRequest.request

    override def action: Function0[Future[Any]] = () ⇒ request(originalRequester)
  }

}

sealed trait LockActor extends Actor {

  import context.dispatcher
  import us.bleibinha.akka.actor.locking.LockActor._

  private var objsInProcess: Set[Any] = Set()
  private var waitingByObj: Map[Any, Queue[LockAwareMessage]] = Map()

  protected def lockAwareReceive: Receive = {
    case lockAware: LockAware ⇒
      val requester = sender()
      val lockAwareMessage = lockAwareToLockAwareMessage(lockAware, requester)
      if (!objsInProcess.contains(lockAware.lockObj))
        executeLockAware(lockAwareMessage)
      else
        addToWaiting(lockAwareMessage)
    case unlockMsg: Unlock    ⇒
      val lockObj = unlockMsg.lockObj
      unlock(lockObj)
      processWaiting(lockObj)
  }

  private def executeLockAware(lockAwareMessage: LockAwareMessage) {
    val lockObj = lockAwareMessage.lockObj
    lock(lockObj)
    val resultFuture = lockAwareMessage.action.apply
    resultFuture onComplete {
      _ ⇒ self ! Unlock(lockObj)
    }
  }

  private def lock(lockObj: Any) {
    objsInProcess = objsInProcess + lockObj
  }

  private def unlock(lockObj: Any) {
    objsInProcess = objsInProcess - lockObj
  }

  private def processWaiting(lockObj: Any): Unit =
    waitingByObj.get(lockObj) foreach { waitingMessages ⇒
      waitingMessages.length match {
        case x if x > 1 ⇒
          val (waitingMessage, moreWaitingMessages) = waitingMessages.dequeue
          waitingByObj = waitingByObj + (lockObj → moreWaitingMessages)
          executeLockAware(waitingMessage)
        case 1          ⇒
          val (waitingMessage, _) = waitingMessages.dequeue
          waitingByObj = waitingByObj - lockObj
          executeLockAware(waitingMessage)
        case 0          ⇒
          waitingByObj = waitingByObj - lockObj
      }
    }

  private def addToWaiting(lockAwareMessage: LockAwareMessage) {
    val lockObj = lockAwareMessage.lockObj
    val waitingMessagesForObj = waitingByObj.getOrElse(lockObj, Queue()).enqueue(lockAwareMessage)
    waitingByObj = waitingByObj + (lockObj → waitingMessagesForObj)
  }

  private def lockAwareToLockAwareMessage(lockAware: LockAware, originalRequester: ActorRef): LockAwareMessage =
    lockAware match {
      case lockAwareRequest: LockAwareRequest ⇒ LockAwareWithRequester(lockAwareRequest, originalRequester)
      case lockAwareMessage: LockAwareMessage ⇒ lockAwareMessage
    }
}
