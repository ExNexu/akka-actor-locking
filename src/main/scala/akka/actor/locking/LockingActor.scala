package us.bleibinha.akka.actor.locking

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor.Actor

object LockingActor {
  trait LockAwareMessage {
    def lockObj: Any
    def action: Function0[Future[Any]]
    def expireLockAfter: Option[FiniteDuration] = None
  }
  object LockAwareMessage {
    def apply(lockObject: Any, actionFunction: Function0[Future[Any]]): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = actionFunction
      }

    def apply(lockObject: Any, actionFunction: Function0[Future[Any]], expireLockAfterTime: FiniteDuration): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = actionFunction
        override val expireLockAfter = Some(expireLockAfterTime)
      }

    def apply(lockObject: Any, actionFunction: Function0[Any])(implicit dI: DummyImplicit): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = () ⇒ Future.successful(actionFunction.apply)
      }

    def apply(lockObject: Any, actionFunction: Function0[Any], expireLockAfterTime: FiniteDuration)(implicit dI: DummyImplicit): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = () ⇒ Future.successful(actionFunction.apply)
        override val expireLockAfter = Some(expireLockAfterTime)
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

  private var timeByObjInProcess: Map[Any, Long] = Map()
  private var deadlineByObj: Map[Any, Deadline] = Map()
  private var waitingMessagesByObj: Map[Any, List[LockAwareMessage]] = Map()

  protected def defaultExpireLockAfter: Option[FiniteDuration] = None

  protected def lockAwareReceive: Receive = {
    case lockAwareMessage: LockAwareMessage ⇒
      val lockObj = lockAwareMessage.lockObj
      getLockedSince(lockObj) match {
        case None ⇒
          process(lockAwareMessage)
        case Some(_) if isOverdue(lockObj) ⇒
          process(lockAwareMessage)
        case _ ⇒
          addToWaitingMessages(lockObj, lockAwareMessage)
      }
    case unlockMessage: UnlockMessage ⇒
      val lockObj = unlockMessage.lockObj
      unlock(lockObj)
      triggerWaitingMessages(lockObj)
    case TriggerWaitingMessages(lockObj) ⇒
      triggerWaitingMessages(lockObj)
  }

  private def isOverdue(lockObj: Any): Boolean =
    deadlineByObj.get(lockObj) match {
      case None           ⇒ false
      case Some(deadline) ⇒ deadline.isOverdue
    }

  private def process(lockAwareMessage: LockAwareMessage) {
    val lockObj = lockAwareMessage.lockObj
    val deadline: Option[Deadline] =
      lockAwareMessage.expireLockAfter.orElse(defaultExpireLockAfter) map (_.fromNow)
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

  private def getLockedSince(lockObj: Any): Option[Long] =
    timeByObjInProcess.get(lockObj)

  private def lock(lockObj: Any, deadline: Option[Deadline]) {
    timeByObjInProcess = timeByObjInProcess + (lockObj → now())
    deadline map { deadline ⇒
      deadlineByObj = deadlineByObj + (lockObj → deadline)
      scheduleTriggerWaitingMessages(lockObj, deadline.timeLeft)
    }
  }

  private def unlock(lockObj: Any) {
    timeByObjInProcess = timeByObjInProcess - lockObj
    deadlineByObj = deadlineByObj - lockObj
  }

  private def triggerWaitingMessages(lockObj: Any) {
    waitingMessagesByObj.get(lockObj) map { waitingMessages ⇒
      waitingMessages.reverse match {
        case waitingMessage :: Nil ⇒
          waitingMessagesByObj = waitingMessagesByObj - lockObj
          self ! waitingMessage
        case waitingMessage :: moreWaitingMessages ⇒
          waitingMessagesByObj = waitingMessagesByObj + (lockObj → moreWaitingMessages.reverse)
          self ! waitingMessage
        case Nil ⇒
          waitingMessagesByObj = waitingMessagesByObj - lockObj
      }
    }
  }

  private def addToWaitingMessages(lockObj: Any, lockAwareMessage: LockAwareMessage) {
    val waitingMessagesForObj = lockAwareMessage :: waitingMessagesByObj.get(lockObj).getOrElse(Nil)
    waitingMessagesByObj = waitingMessagesByObj + (lockObj → waitingMessagesForObj)
  }

  private def now() = System.currentTimeMillis
}
