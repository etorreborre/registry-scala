package registry.cats

import _root_.cats.Applicative
import _root_.cats.syntax.all.*

private[cats] object Combine:
  /** Combine a sequence of `F[?]` values into a single `F[T]` via an `Applicative[F]`, by sequencing
   * each effect (using `product`) and then applying `build` to the collected values. Used by the
   * `funTo` macro-emitted closures. */
  def combineF[F[_]: Applicative, T](fs: Seq[Any], build: Seq[Any] => T): F[T] =
    if fs.isEmpty then Applicative[F].pure(build(Nil))
    else
      fs.foldLeft(Applicative[F].pure(Vector.empty[Any])) { (accF, fa) =>
          Applicative[F]
            .product(accF, fa.asInstanceOf[F[Any]])
            .map { case (vs, v) => vs :+ v }
        }
        .map(vs => build(vs.toSeq))
