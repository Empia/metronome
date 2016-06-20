package dcos.metronome

import com.softwaremill.macwire._
import controllers.Assets
import dcos.metronome.scheduler.SchedulerModule
import dcos.metronome.api.v1.LeaderProxyFilter
import dcos.metronome.api.{ ApiConfig, ApiModule, ErrorHandler }
import dcos.metronome.repository.impl.kv.ZkConfig
import dcos.metronome.utils.time.{ Clock, SystemClock }
import mesosphere.marathon.AllConf
import org.asynchttpclient.AsyncHttpClientConfig
import org.joda.time.DateTimeZone
import play.api.ApplicationLoader.Context
import play.api._
import play.api.i18n._
import play.api.libs.ws.ahc.{ AhcWSClient, AhcConfigBuilder, AhcWSClientConfig }
import play.api.libs.ws.{ WSConfigParser, WSClient }
import play.api.mvc.EssentialFilter
import play.api.routing.Router

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Application loader that wires up the application dependencies using Macwire
  */
class JobApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    val jobComponents = new JobComponents(context)

    Future {
      jobComponents.schedulerService.run()
    }(scala.concurrent.ExecutionContext.global)

    jobComponents.application
  }
}

class JobComponents(context: Context) extends BuiltInComponentsFromContext(context) with I18nComponents {
  // set up logger
  LoggerConfigurator(context.environment.classLoader).foreach {
    _.configure(context.environment)
  }
  lazy val assets: Assets = wire[Assets]

  lazy val clock: Clock = new SystemClock(DateTimeZone.UTC)

  override lazy val httpErrorHandler = new ErrorHandler

  private[this] lazy val jobsModule: JobsModule = wire[JobsModule]

  private[this] lazy val apiModule: ApiModule = new ApiModule(
    config,
    jobsModule.jobSpecModule.jobSpecService,
    jobsModule.jobRunModule.jobRunService,
    jobsModule.jobInfoModule.jobInfoService,
    jobsModule.pluginManger,
    httpErrorHandler,
    jobsModule.behaviorModule.metrics,
    assets
  )

  def schedulerService = jobsModule.schedulerModule.schedulerService

  lazy val wsClient: WSClient = {
    val parser = new WSConfigParser(configuration, environment)
    val config = new AhcWSClientConfig(wsClientConfig = parser.parse())
    val builder = new AhcConfigBuilder(config)
    val logging = new AsyncHttpClientConfig.AdditionalChannelInitializer() {
      override def initChannel(channel: io.netty.channel.Channel): Unit = {
        channel.pipeline.addFirst("log", new io.netty.handler.logging.LoggingHandler(classOf[WSClient]))
      }
    }
    val ahcBuilder = builder.configure()
    ahcBuilder.setHttpAdditionalChannelInitializer(logging)
    val ahcConfig = ahcBuilder.build()
    new AhcWSClient(ahcConfig)
  }

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(
    new LeaderProxyFilter(wsClient, jobsModule.schedulerModule.electionService, config)
  )

  override def router: Router = apiModule.router

  lazy val config = new Object with JobsConfig with ApiConfig {

    lazy val master: String = "localhost:5050"

    lazy val pluginDir: Option[String] = configuration.getString("app.plugin.dir")
    lazy val pluginConf: Option[String] = configuration.getString("app.plugin.conf")
    lazy val runHistoryCount: Int = configuration.getInt("app.history.count").getOrElse(10)
    lazy val withMetrics: Boolean = configuration.getBoolean("app.behavior.metrics").getOrElse(true)

    lazy val disableHttp: Boolean = configuration.getBoolean("play.server.http.disableHttp").getOrElse(false)
    lazy val httpPort: Int = configuration.getInt("play.server.http.port").getOrElse(9000)
    lazy val httpsPort: Int = configuration.getInt("play.server.https.port").getOrElse(9443)
    lazy val hostname: String = configuration.getString("app.hostname").getOrElse(java.net.InetAddress.getLocalHost.getHostName)
    lazy val leaderProxyTimeout: Duration = configuration.getLong("app.leaderproxy.timeout").getOrElse(30000l).millis

    lazy val askTimeout: FiniteDuration = configuration.getLong("app.akka.ask.timeout").getOrElse(10000l).millis

    override lazy val zkURL: String = configuration.getString("app.zk.url").getOrElse(ZkConfig.DEFAULT_ZK_URL)
    override lazy val zkSessionTimeout: FiniteDuration = configuration.getLong("app.zk.session_timeout")
      .map(_.millis)
      .getOrElse(ZkConfig.DEFAULT_ZK_SESSION_TIMEOUT)
    override lazy val zkTimeout: FiniteDuration = configuration.getLong("app.zk.timeout")
      .map(_.millis)
      .getOrElse(ZkConfig.DEFAULT_ZK_TIMEOUT)
    override lazy val zkCompressionEnabled: Boolean = configuration.getBoolean("app.zk.compression.enabled")
      .getOrElse(ZkConfig.DEFAULT_ZK_COMPRESSION_ENABLED)
    override lazy val zkCompressionThreshold: Long = configuration.getLong("app.zk.compression.threshold")
      .getOrElse(ZkConfig.DEFAULT_ZK_COMPRESSION_THRESHOLD)

    lazy val scallopConf: AllConf = {
      val flags = Seq[Option[String]](
        if (disableHttp) Some("--disable_http") else None,
        if (zkCompressionEnabled) Some("--zk_compression") else None
      )
      val options = Map[String, Option[String]](
        "--framework_name" -> Some("metronome"),
        "--master" -> Some(master),
        "--plugin_dir" -> pluginDir,
        "--plugin_conf" -> pluginConf,
        "--http_port" -> Some(httpPort.toString),
        "--https_port" -> Some(httpsPort.toString),
        "--hostname" -> Some(hostname),
        "--zk" -> Some(zkURL),
        "--zk_session_timeout" -> Some(zkSessionTimeout.toMillis.toString),
        "--zk_timeout" -> Some(zkTimeout.toMillis.toString),
        "--zk_compression_threshold" -> Some(zkCompressionThreshold.toString)
      )
        .collect { case (name, Some(value)) => (name, value) }
        .flatMap { case (name, value) => Seq(name, value) }
      new AllConf(options.toSeq ++ flags.flatten)
    }

    //TODO: those values need to be configured via play - not the other way around
    lazy val mesosLeaderUiUrl: Option[String] = scallopConf.mesosLeaderUiUrl.get

    lazy val reconciliationInterval: FiniteDuration = configuration.getLong("scheduler.reconciliation.interval").getOrElse(15 * 60 * 1000L).milliseconds
    lazy val reconciliationTimeout: FiniteDuration = configuration.getLong("scheduler.reconciliation.timeout").getOrElse(60 * 1000L).milliseconds
  }
}
