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
