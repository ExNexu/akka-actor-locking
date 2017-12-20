package us.bleibinha.akka.actor.locking

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.pattern.pipe

trait LockActorInterface {
  sealed trait LockAware {
    def lockObj: Any
  }

  trait LockAwareMessage extends LockAware {
    def action: Function0[Future[Any]]
  }

  object LockAwareMessage {
    def apply(lockObj: Any, actionFunction: Function0[Future[Any]])(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareMessage =
      LockAwareMessageImpl(lockObj, () ⇒ Future(actionFunction.apply).flatMap(identity))

    def apply(lockObj: Any, actionFunction: Function0[Any])(implicit ec: ExecutionContext): LockAwareMessage =
      LockAwareMessageImpl(lockObj, () ⇒ Future(actionFunction.apply))
  }

  trait LockAwareRequest extends LockAware {
    def request: Function1[ActorRef, Future[Any]]
  }

  object LockAwareRequest {
    def apply(lockObj: Any, requestFunction: Function0[Future[Any]])(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareRequest =
      LockAwareRequestImpl(lockObj, LockAwareRequestImpl.requestFromRequestFunction(requestFunction))

    def apply(lockObj: Any, requestFunction: Function0[Any])(implicit ec: ExecutionContext): LockAwareRequest =
      LockAwareRequestImpl(lockObj, LockAwareRequestImpl.requestFromRequestFunction(requestFunction))
  }

  trait Unlock {
    def lockObj: Any
  }
  object Unlock {
    def apply(lockObject: Any): Unlock = new Unlock {
      override val lockObj = lockObject
    }
  }

  private[LockActorInterface] case class LockAwareMessageImpl(
    lockObj: Any,
    action: Function0[Future[Any]])
    extends LockAwareMessage

  private[LockActorInterface] case class LockAwareRequestImpl(
    lockObj: Any,
    request: Function1[ActorRef, Future[Any]])
    extends LockAwareRequest

  private[LockActorInterface] object LockAwareRequestImpl {
    def requestFromRequestFunction(requestFunction: Function0[Future[Any]])(implicit ec: ExecutionContext, di: DummyImplicit) =
      (requester: ActorRef) ⇒ {
        val result = requestFunction.apply
        result pipeTo requester
        result
      }

    def requestFromRequestFunction(requestFunction: Function0[Any])(implicit ec: ExecutionContext) =
      (requester: ActorRef) ⇒ {
        val result = Future(requestFunction.apply)
        result pipeTo requester
        result
      }
  }
}
