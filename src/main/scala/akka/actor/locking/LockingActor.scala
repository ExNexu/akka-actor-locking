package us.bleibinha.akka.actor.locking

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.pipe

object LockingActor {

  def apply()(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new DefaultLockingActor(None)))
  def apply(defaultLockExpiration: FiniteDuration)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new DefaultLockingActor(Some(defaultLockExpiration))))

  trait LockAware {
    def lockObj: Any
    def lockExpiration: Option[FiniteDuration] = None
  }

  trait LockAwareMessage extends LockAware {
    def action: Function0[Future[Any]]
  }
  object LockAwareMessage {
    def apply(lockObject: Any, actionFunction: Function0[Future[Any]])(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = () ⇒ Future(actionFunction.apply).flatMap(identity)
      }

    def apply(lockObject: Any, actionFunction: Function0[Future[Any]], lockExpirationDuration: FiniteDuration)(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareMessage =
      new LockAwareMessage {
        override val lockObj = lockObject
        override val action = () ⇒ Future(actionFunction.apply).flatMap(identity)
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

  trait LockAwareRequest extends LockAware {
    def request: Function1[ActorRef, Future[Any]]
  }
  object LockAwareRequest {
    def apply(lockObject: Any, actionFunction: Function0[Future[Any]])(implicit ec: ExecutionContext): LockAwareRequest =
      new LockAwareRequest {
        override val lockObj = lockObject
        override val request =
          (requester: ActorRef) ⇒ {
            val result = Future(actionFunction.apply).flatMap(identity)
            result pipeTo requester
            result
          }
      }
  }

  trait Unlock {
    def lockObj: Any
  }
  object Unlock {
    def apply(lockObject: Any): Unlock = new Unlock {
      override val lockObj = lockObject
    }
  }

  private[LockingActor] case class TriggerWaiting(lockObj: Any)
  private[LockingActor] case class LockAwareWithRequester(lockAwareRequest: LockAwareRequest, originalRequester: ActorRef) extends LockAware {
    override def lockObj = lockAwareRequest.lockObj
  }
}

trait LockingActor extends Actor {

  import LockingActor._
  import context.dispatcher
  import context.system

  private var objsInProcess: Set[Any] = Set()
  private var deadlineByObj: Map[Any, Deadline] = Map()
  private var waitingByObj: Map[Any, Queue[LockAware]] = Map()

  protected def defaultLockExpiration: Option[FiniteDuration] = None

  protected def lockAwareReceive: Receive = {
    case lockAware: LockAware ⇒
      val lockObj = lockAware.lockObj
      objsInProcess.contains(lockObj) match {
        case false ⇒
          processLockAware(lockAware)
        case true if isOverdue(lockObj) ⇒
          processLockAware(lockAware)
        case _ ⇒
          addToWaiting(lockObj, lockAware, sender())
      }
    case unlockMsg: Unlock ⇒
      val lockObj = unlockMsg.lockObj
      unlock(lockObj)
      self ! TriggerWaiting(lockObj)
    case TriggerWaiting(lockObj) ⇒
      triggerWaitingMessages(lockObj)
  }

  private def isOverdue(lockObj: Any): Boolean =
    deadlineByObj.get(lockObj) match {
      case None           ⇒ false
      case Some(deadline) ⇒ deadline.isOverdue
    }

  private def processLockAware(lockAware: LockAware) {
    val lockObj = lockAware.lockObj
    val deadline: Option[Deadline] =
      lockAware.lockExpiration.orElse(defaultLockExpiration) map (_.fromNow)
    lock(lockObj, deadline)
    val resultFuture = lockAware match {
      case lockAwareMessage: LockAwareMessage ⇒
        lockAwareMessage.action.apply
      case lockAwareRequest: LockAwareRequest ⇒
        val requester = sender()
        lockAwareRequest.request(requester)
      case lockAwareWithRequester: LockAwareWithRequester ⇒
        val requester = lockAwareWithRequester.originalRequester
        lockAwareWithRequester.lockAwareRequest.request(requester)
    }
    resultFuture onComplete {
      case _ ⇒ self ! Unlock(lockObj)
    }
  }

  private def lock(lockObj: Any, deadline: Option[Deadline]) {
    objsInProcess = objsInProcess + lockObj
    deadline map { deadline ⇒
      deadlineByObj = deadlineByObj + (lockObj → deadline)
      scheduleTriggerWaiting(lockObj, deadline.timeLeft)
    }
  }

  private def scheduleTriggerWaiting(lockObj: Any, afterTime: FiniteDuration) =
    system.scheduler.scheduleOnce(afterTime) {
      self ! TriggerWaiting(lockObj)
    }

  private def unlock(lockObj: Any) {
    objsInProcess = objsInProcess - lockObj
    deadlineByObj = deadlineByObj - lockObj
  }

  private def triggerWaitingMessages(lockObj: Any) {
    waitingByObj.get(lockObj) map { waitingMessages ⇒
      waitingMessages.dequeueOption match {
        case Some((waitingMessage, Queue())) ⇒
          waitingByObj = waitingByObj - lockObj
          self ! waitingMessage
        case Some((waitingMessage, moreWaitingMessages)) ⇒
          waitingByObj = waitingByObj + (lockObj → moreWaitingMessages)
          self ! waitingMessage
        case None ⇒
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
    val waitingMessagesForObj = waitingByObj.get(lockObj).getOrElse(Queue()).enqueue(modLockAware)
    waitingByObj = waitingByObj + (lockObj → waitingMessagesForObj)
  }
}

class DefaultLockingActor(override protected val defaultLockExpiration: Option[FiniteDuration]) extends LockingActor {
  override def receive = lockAwareReceive
}
