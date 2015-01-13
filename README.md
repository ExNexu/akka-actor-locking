# akka-actor-locking

## Introduction

**A small Scala [akka](http://akka.io/) library for locking critical sections of code using a binary semaphore without blocking.** The use case is primarily for interacting with external sources (i.e. preventing parallel requests to a website, executing a transaction in a database without transaction support, ...). You should not need to lock on code which stays inside your application (Use a single actor instead).

Simple example:

```scala
val lock1 = "LOCK" // the lockObj can be of any type
val action1 = () ⇒ { someCode() } // your code is executed in a future
val action2 = () ⇒ { someOtherCode() } // the lock is released when your code returns

val lock2 = 1337
val action3 = () ⇒ { someReallyOtherCode() }

lockActor ! LockAwareMessage(lock1, action1) // order is guaranteed
lockActor ! LockAwareMessage(lock1, action2) // runs right after action1 :-)
lockActor ! LockAwareMessage(lock2, action3) // runs in parallel to action1 & action2
```

## Get started

Add this to your `build.sbt`:

```scala
resolvers += "bleibinha.us/archiva releases" at "http://bleibinha.us/archiva/repository/releases"

libraryDependencies ++= Seq(
  "us.bleibinha" %% "akka-actor-locking" % "0.0.2"
)
```

Get the lockActor:

```scala
import us.bleibinha.akka.actor.locking.LockActor._ // imports everything needed

implicit val actorSystem = ActorSystem() // is an implicit argument to the LockActor
lockActor = LockActor()
```

## Features

* Support for action code with a Future return type. The lock will be active until the returned Future completes (**Warning**: Make sure the returned Future 'waits' on all others if you are using multiple Futures in the action block. [Future.sequence](http://www.scala-lang.org/api/current/index.html#scala.concurrent.Future$) might come in handy).
```scala
val action = () ⇒ {
  someCode()
  Future { someMoreCode() }
}
lockActor ! LockAwareMessage(lockObj, action)
```
* `LockAwareRequest` sends back the result to the requesting actor. This is practical in combination with the [ask pattern](http://doc.akka.io/docs/akka/snapshot/scala/actors.html#Ask__Send-And-Receive-Future). If you are already inside an actor, you can also use a `LockAwareMessage` and `self ! Something` to reply.
```scala
val request = () ⇒ "Hello"
val result = lockActor.ask(LockAwareRequest(lockObj, request))
result map println // prints "Hello"
```
* All action code is executed in a wrapping `Future` not blocking the lockActor from other requests.
* The code is non-blocking.
* Expiration on locks. Expires the lock
  * in the lockActor (default timeout for all requests).
  ```scala
  import scala.concurrent.duration._

  lockActor = LockActor(30 seconds)
  ```
  * in the message (overwrites default timeout).
  ```scala
  lockActor ! LockAwareMessage(lockObj, action, 30 seconds)
  ```
* Manually release a lock.
```scala
lockActor ! Unlock(lockObj)
```
* Order of incoming messages to the lockActor is maintained.
* The lock is released when the action block code throws an exception.
* Tested. (TODO: Link to tests here)

## License

[Apache License, version 2.0](https://github.com/ExNexu/akka-actor-locking/blob/master/LICENSE)
