package us.bleibinha.akka.actor.locking

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props

object LockingActor {

  def apply()(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new DefaultLockingActor(None)))
  def apply(defaultLockExpiration: FiniteDuration)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new DefaultLockingActor(Some(defaultLockExpiration))))

  trait LockAwareMessage {
    def lockObj: Any
    def action: Function0[Future[Any]]
    def lockExpiration: Option[FiniteDuration] = None
  }
  object LockAwareMessage {
    def apply(lockObject: Any, actionFunction: Function0[Future[Any]]): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = actionFunction
      }

    def apply(lockObject: Any, actionFunction: Function0[Future[Any]], lockExpirationDuration: FiniteDuration): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = actionFunction
        override val lockExpiration = Some(lockExpirationDuration)
      }

    def apply(lockObject: Any, actionFunction: Function0[Any])(implicit ec: ExecutionContext): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = () ⇒ Future { actionFunction.apply }
      }

    def apply(lockObject: Any, actionFunction: Function0[Any], lockExpirationDuration: FiniteDuration)(implicit ec: ExecutionContext): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = () ⇒ Future { actionFunction.apply }
        override val lockExpiration = Some(lockExpirationDuration)
      }
  }

  trait UnlockMessage {
    def lockObj: Any
  }
  object UnlockMessage {
    def apply(lockObject: Any): UnlockMessage = new UnlockMessage {
      override val lockObj = lockObject
    }
  }

  private[LockingActor] case class TriggerWaitingMessages(lockObj: Any)
}

trait LockingActor extends Actor {

  import LockingActor._
  import context.dispatcher
  import context.system

  private var objsInProcess: Set[Any] = Set()
  private var deadlineByObj: Map[Any, Deadline] = Map()
  private var waitingMessagesByObj: Map[Any, Queue[LockAwareMessage]] = Map()

  protected def defaultLockExpiration: Option[FiniteDuration] = None

  protected def lockAwareReceive: Receive = {
    case lockAwareMessage: LockAwareMessage ⇒
      val lockObj = lockAwareMessage.lockObj
      objsInProcess.contains(lockObj) match {
        case false ⇒
          processMessage(lockAwareMessage)
        case true if isOverdue(lockObj) ⇒
          processMessage(lockAwareMessage)
        case _ ⇒
          addToWaitingMessages(lockObj, lockAwareMessage)
      }
    case unlockMessage: UnlockMessage ⇒
      val lockObj = unlockMessage.lockObj
      unlock(lockObj)
      self ! TriggerWaitingMessages(lockObj)
    case TriggerWaitingMessages(lockObj) ⇒
      triggerWaitingMessages(lockObj)
  }

  private def isOverdue(lockObj: Any): Boolean =
    deadlineByObj.get(lockObj) match {
      case None           ⇒ false
      case Some(deadline) ⇒ deadline.isOverdue
    }

  private def processMessage(lockAwareMessage: LockAwareMessage) {
    val lockObj = lockAwareMessage.lockObj
    val deadline: Option[Deadline] =
      lockAwareMessage.lockExpiration.orElse(defaultLockExpiration) map (_.fromNow)
    lock(lockObj, deadline)
    val action = lockAwareMessage.action
    val actionFuture = action.apply
    actionFuture onComplete {
      case _ ⇒ self ! UnlockMessage(lockObj)
    }
  }

  private def scheduleTriggerWaitingMessages(lockObj: Any, afterTime: FiniteDuration) =
    system.scheduler.scheduleOnce(afterTime) {
      self ! TriggerWaitingMessages(lockObj)
    }

  private def lock(lockObj: Any, deadline: Option[Deadline]) {
    objsInProcess = objsInProcess + lockObj
    deadline map { deadline ⇒
      deadlineByObj = deadlineByObj + (lockObj → deadline)
      scheduleTriggerWaitingMessages(lockObj, deadline.timeLeft)
    }
  }

  private def unlock(lockObj: Any) {
    objsInProcess = objsInProcess - lockObj
    deadlineByObj = deadlineByObj - lockObj
  }

  private def triggerWaitingMessages(lockObj: Any) {
    waitingMessagesByObj.get(lockObj) map { waitingMessages ⇒
      waitingMessages.dequeueOption match {
        case Some((waitingMessage, Queue())) ⇒
          waitingMessagesByObj = waitingMessagesByObj - lockObj
          self ! waitingMessage
        case Some((waitingMessage, moreWaitingMessages)) ⇒
          waitingMessagesByObj = waitingMessagesByObj + (lockObj → moreWaitingMessages)
          self ! waitingMessage
        case None ⇒
          waitingMessagesByObj = waitingMessagesByObj - lockObj
      }
    }
  }

  private def addToWaitingMessages(lockObj: Any, lockAwareMessage: LockAwareMessage) {
    val waitingMessagesForObj = waitingMessagesByObj.get(lockObj).getOrElse(Queue()).enqueue(lockAwareMessage)
    waitingMessagesByObj = waitingMessagesByObj + (lockObj → waitingMessagesForObj)
  }
}

class DefaultLockingActor(override protected val defaultLockExpiration: Option[FiniteDuration]) extends LockingActor {
  override def receive = lockAwareReceive
}
