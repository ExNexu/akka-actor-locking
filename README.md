# akka-actor-locking

[![Build Status](https://travis-ci.org/ExNexu/akka-actor-locking.svg?branch=master)](https://travis-ci.org/ExNexu/akka-actor-locking) [![Coverage Status](https://coveralls.io/repos/ExNexu/akka-actor-locking/badge.svg?branch=master)](https://coveralls.io/r/ExNexu/akka-actor-locking?branch=master)

**A small Scala [akka](http://akka.io/) library for locking critical sections of code using a binary semaphore without blocking.** The use case is primarily for interacting with external sources (i.e. preventing parallel requests to an external service). You should not need to lock on code which stays inside your application (Use a single actor instead).

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

Add this to your `build.sbt` for Scala 2.12 & 2.11 (last version for Scala 2.10 is `0.0.3`):

```scala
resolvers += "bleibinha.us/archiva releases" at "http://bleibinha.us/archiva/repository/releases"

libraryDependencies ++= Seq(
  "us.bleibinha" %% "akka-actor-locking" % "0.0.6"
)
```

Get the lockActor:

```scala
import akka.actor.ActorSystem
import us.bleibinha.akka.actor.locking._ // imports everything needed

implicit val actorSystem = ActorSystem() // is an implicit argument to the LockActor
val lockActor = LockActor()
```

## Features

* Support for action code with a Future return type. The lock will be active until the returned Future completes. **Warning**: Make sure the returned Future 'waits' on all others if you are using multiple Futures in the action block. [Future.sequence](http://www.scala-lang.org/api/current/index.html#scala.concurrent.Future$) might come in handy.
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
* Manually release a lock.
```scala
lockActor ! Unlock(lockObj)
```
* Order of incoming messages to the lockActor is maintained.
* The lock is released when the action code throws an exception.
* Built with *Scala 2.12.4* & *Scala 2.11.12* and *akka 2.5.8*.
* [Tested](https://github.com/ExNexu/akka-actor-locking/blob/master/src/test/scala/akka/actor/locking/LockActorTest.scala).

## License

[Apache License, version 2.0](https://github.com/ExNexu/akka-actor-locking/blob/master/LICENSE)
