package registry

object Chain:
  case class Host(value: String)
  case class AppName(value: String)
  case class DbConfig(host: Host, port: Int)
  case class Db(config: DbConfig)
  case class App(db: Db, name: AppName)

object Plain:
  import Chain.Host
  class Service(val host: Host, val port: Int)

  class Bare(host: Host, port: Int):
    def describe: String = s"Host(${host.value})@$port"

  class Multi(host: Host)(port: Int):
    def describe: String = s"${host.value}-$port"

  class WithUsing(host: Host)(using port: Int):
    def describe: String = s"${host.value}:$port"

  class WithImplicit(host: Host)(implicit port: Int):
    def describe: String = s"${host.value}#$port"

object Cycle:
  case class A(b: B)
  case class B(a: A)

object Subtype:

  trait Iface:
    def label: String

  case class Impl(label: String) extends Iface

case class Wrap(value: Int)

case class Person(name: String)

object NameClash:
  // Two distinct types named `Coin` — same short name, different packages. Used to verify
  // the macro error formatter disambiguates colliding short names.
  object first:
    case class Coin(value: Long)
  object second:
    case class Coin(value: Long)
  case class NeedsFirst(coin: first.Coin)

// Fixtures for the share-within-a-make tests. Top-level so `fun[T]` macros can reflect on them.
case class ShareLogger(prefix: String)
case class ShareServer(log: ShareLogger)
case class ShareWorker(log: ShareLogger)
case class ShareSpecApp(s: ShareServer, w: ShareWorker)
