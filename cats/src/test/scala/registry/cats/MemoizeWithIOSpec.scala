package registry.cats

import org.specs2.mutable.Specification
import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import registry.*

/**
 * Shows how `memoize` and cats-effect `IO` interact:
 *
 *  1. `registry.memoize[IO[T]]` caches the **`IO[T]` value** — every `make[IO[T]]` returns the same
 *     `IO[T]` reference. Running that IO *still* re-executes the effect each time (IO has no built-in
 *     memoization).
 *  2. To also memoize the **result of running** the effect, stack cats-effect's `IO.memoize` on top
 *     before registering. Concretely: run the outer `IO[IO[T]]` once to materialize the shared cell,
 *     then register the inner `IO[T]` — every `unsafeRunSync` returns the cached result.
 */
class MemoizeWithIOSpec extends Specification:

  "registry.memoize + IO" should {
    "cache the IO[T] value itself — the IO is re-run on each call but the value is shared" >> {
      val counter              = new java.util.concurrent.atomic.AtomicInteger(0)
      val acquire: IO[Service] = IO.delay { counter.incrementAndGet(); new Service() }

      val r = (value(acquire) +: Registry.empty).memoize[IO[Service]]

      val io1 = r.make[IO[Service]]
      val io2 = r.make[IO[Service]]
      io1 must beTheSameAs(io2) // same IO value from the registry cache

      // Running it twice still runs the effect twice — IO itself is not memoized.
      io1.unsafeRunSync()
      io1.unsafeRunSync()
      counter.get() === 2
    }

    "stacking IO.memoize gives full result-level memoization" >> {
      val counter              = new java.util.concurrent.atomic.AtomicInteger(0)
      val acquire: IO[Service] = IO.delay { counter.incrementAndGet(); new Service() }

      // `acquire.memoize` is `IO[IO[Service]]` — an IO that creates a fresh memoized cell each time it
      // runs. Run it once to materialize the cell, then register the inner IO: every subsequent run
      // returns the cached result.
      val memoizedIO: IO[Service] = acquire.memoize.unsafeRunSync()

      val r  = value(memoizedIO) +: Registry.empty
      val io = r.make[IO[Service]]

      val s1 = io.unsafeRunSync()
      val s2 = io.unsafeRunSync()
      s1 must beTheSameAs(s2)
      counter.get() === 1
    }
  }

class Service
