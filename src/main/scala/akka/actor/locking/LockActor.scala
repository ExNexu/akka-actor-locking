package us.bleibinha.akka.actor.locking

import scala.collection.immutable.Queue

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props

object LockActor {

  def apply()(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new DefaultLockActor()))

  private[LockActor] class DefaultLockActor() extends LockActor {
    override def receive = lockAwareReceive
  }

  private[LockActor] case class TriggerWaiting(lockObj: Any)

  private[LockActor] case class LockAwareWithRequester(lockAwareRequest: LockAwareRequest, originalRequester: ActorRef) extends LockAwareRequest {
    override def lockObj = lockAwareRequest.lockObj
    override def request = lockAwareRequest.request
  }
}

sealed trait LockActor extends Actor {

  import LockActor._
  import context.dispatcher

  private var objsInProcess: Set[Any] = Set()
  private var waitingByObj: Map[Any, Queue[LockAware]] = Map()

  protected def lockAwareReceive: Receive = {
    case lockAware: LockAware ⇒
      val requester = sender()
      val lockObj = lockAware.lockObj
      objsInProcess.contains(lockObj) match {
        case false ⇒
          processLockAware(lockAware, requester)
        case _ ⇒
          addToWaiting(lockObj, lockAware, requester)
      }
    case unlockMsg: Unlock ⇒
      val lockObj = unlockMsg.lockObj
      unlock(lockObj)
      self ! TriggerWaiting(lockObj)
    case TriggerWaiting(lockObj) ⇒
      triggerWaitingMessages(lockObj)
  }

  private def processLockAware(lockAware: LockAware, requester: ActorRef) {
    val lockObj = lockAware.lockObj
    lock(lockObj)
    val resultFuture = lockAware match {
      case lockAwareMessage: LockAwareMessage ⇒
        lockAwareMessage.action.apply
      case lockAwareWithRequester: LockAwareWithRequester ⇒
        val requester = lockAwareWithRequester.originalRequester
        lockAwareWithRequester.request(requester)
      case lockAwareRequest: LockAwareRequest ⇒
        lockAwareRequest.request(requester)
    }
    resultFuture onComplete {
      case _ ⇒ self ! Unlock(lockObj)
    }
  }

  private def lock(lockObj: Any) {
    objsInProcess = objsInProcess + lockObj
  }

  private def unlock(lockObj: Any) {
    objsInProcess = objsInProcess - lockObj
  }

  private def triggerWaitingMessages(lockObj: Any) {
    waitingByObj.get(lockObj) foreach { waitingMessages ⇒
      waitingMessages.length match {
        case x if x > 1 ⇒
          val (waitingMessage, moreWaitingMessages) = waitingMessages.dequeue
          waitingByObj = waitingByObj + (lockObj → moreWaitingMessages)
          self ! waitingMessage
        case 1 ⇒
          val (waitingMessage, _) = waitingMessages.dequeue
          waitingByObj = waitingByObj - lockObj
          self ! waitingMessage
        case 0 ⇒
          waitingByObj = waitingByObj - lockObj
      }
    }
  }

  private def addToWaiting(lockObj: Any, lockAware: LockAware, originalRequester: ActorRef) {
    val modLockAware = lockAware match {
      case lockAwareRequest: LockAwareRequest ⇒
        LockAwareWithRequester(lockAwareRequest, originalRequester)
      case x ⇒ x
    }
    val waitingMessagesForObj = waitingByObj.getOrElse(lockObj, Queue()).enqueue(modLockAware)
    waitingByObj = waitingByObj + (lockObj → waitingMessagesForObj)
  }
}
