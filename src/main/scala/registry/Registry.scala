package registry

import izumi.reflect.Tag

final case class Registry(entries: List[Entry]):
  /** Right-associative prepend: `entry +: registry`. LIFO — the head wins for a given output. */
  def +:(entry: Entry): Registry = Registry(entry :: entries)

  /** Merge two registries. The left operand's entries come first, so on duplicate outputs the left wins. */
  def <+>(other: Registry): Registry = Registry(entries ++ other.entries)

  /** Build a value of type T by recursively resolving inputs from this registry. */
  def make[T](using tag: Tag[T]): T =
    Resolve.resolve(this, tag.tag).asInstanceOf[T]

object Registry:
  val empty: Registry = Registry(Nil)
