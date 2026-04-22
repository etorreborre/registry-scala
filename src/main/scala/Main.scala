import registry.*

case class Host(value: String)
case class AppName(value: String)
case class DbConfig(host: Host, port: Int)
case class Db(config: DbConfig)
case class App(db: Db, name: AppName)

@main def hello(): Unit =
  val r =
    fun[App]                 +:
    fun[Db]                  +:
    fun[DbConfig]            +:
    value(Host("localhost")) +:
    value(5432)              +:
    value(AppName("my-app")) +:
    Registry.empty

  val app: App = r.make[App]
  println(app)
