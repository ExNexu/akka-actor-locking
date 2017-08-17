package us.bleibinha.akka.actor.locking

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.pattern.pipe

trait LockActorInterface {
  sealed trait LockAware {
    def lockObj: Any
    def lockExpiration: Option[FiniteDuration]
  }

  trait LockAwareMessage extends LockAware {
    def action: Function0[Future[Any]]
  }

  object LockAwareMessage {
    def apply(lockObj: Any, actionFunction: Function0[Future[Any]])(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareMessage =
      LockAwareMessageImpl(lockObj, () ⇒ Future(actionFunction.apply).flatMap(identity), None)

    def apply(lockObj: Any, actionFunction: Function0[Future[Any]], lockExpiration: FiniteDuration)(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareMessage =
      LockAwareMessageImpl(lockObj, () ⇒ Future(actionFunction.apply).flatMap(identity), Some(lockExpiration))

    def apply(lockObj: Any, actionFunction: Function0[Any])(implicit ec: ExecutionContext): LockAwareMessage =
      LockAwareMessageImpl(lockObj, () ⇒ Future(actionFunction.apply), None)

    def apply(lockObj: Any, actionFunction: Function0[Any], lockExpiration: FiniteDuration)(implicit ec: ExecutionContext): LockAwareMessage =
      LockAwareMessageImpl(lockObj, () ⇒ Future(actionFunction.apply), Some(lockExpiration))
  }

  trait LockAwareRequest extends LockAware {
    def request: Function1[ActorRef, Future[Any]]
  }

  object LockAwareRequest {
    def apply(lockObj: Any, requestFunction: Function0[Future[Any]])(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareRequest =
      LockAwareRequestImpl(lockObj, LockAwareRequestImpl.requestFromRequestFunction(requestFunction), None)

    def apply(lockObj: Any, requestFunction: Function0[Future[Any]], lockExpiration: FiniteDuration)(implicit ec: ExecutionContext, di: DummyImplicit): LockAwareRequest =
      LockAwareRequestImpl(lockObj, LockAwareRequestImpl.requestFromRequestFunction(requestFunction), Some(lockExpiration))

    def apply(lockObj: Any, requestFunction: Function0[Any])(implicit ec: ExecutionContext): LockAwareRequest =
      LockAwareRequestImpl(lockObj, LockAwareRequestImpl.requestFromRequestFunction(requestFunction), None)

    def apply(lockObj: Any, requestFunction: Function0[Any], lockExpiration: FiniteDuration)(implicit ec: ExecutionContext): LockAwareRequest =
      LockAwareRequestImpl(lockObj, LockAwareRequestImpl.requestFromRequestFunction(requestFunction), Some(lockExpiration))
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
    action: Function0[Future[Any]],
    lockExpiration: Option[FiniteDuration])
    extends LockAwareMessage

  private[LockActorInterface] case class LockAwareRequestImpl(
    lockObj: Any,
    request: Function1[ActorRef, Future[Any]],
    lockExpiration: Option[FiniteDuration])
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
