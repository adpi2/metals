package scala.meta.internal.metals

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ScheduledExecutorService

import scala.annotation.tailrec
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.Messages.OldBloopVersionRunning
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BspConnection
import bloop.rifle.BspConnectionAddress
import dev.dirs.ProjectDirectories
import scala.util.Try
// import coursier.cache.shaded.dirs.ProjectDirectories
// import coursier.cache.shaded.dirs.GetWinDirs

/**
 * Establishes a connection with a bloop server using Bloop Launcher.
 *
 * Connects to a running bloop server instance if it is installed on the user
 * machine and starts a new one if it isn't. Alternatively user can use the
 * coursier command to launch it:
 *
 * coursier launch ch.epfl.scala:bloopgun-core_2.12:{bloop-version} -- about
 *
 * Eventually, this class may be superseded by "BSP connection protocol":
 * https://build-server-protocol.github.io/docs/server-discovery.html
 */
final class BloopServers(
    client: MetalsBuildClient,
    languageClient: MetalsLanguageClient,
    tables: Tables,
    serverConfig: MetalsServerConfig,
    workDoneProgress: WorkDoneProgress,
    sh: ScheduledExecutorService,
    projectRoot: AbsolutePath,
)(implicit ec: ExecutionContextExecutorService) {

  import BloopServers._

  private def metalsJavaHome = sys.props.get("java.home")

  def shutdownServer(): Boolean = {
    // user config is just useful for starting
    val retCode = BloopRifle.exit(
      bloopConfig(userConfig = None),
      projectRoot.toNIO,
      bloopLogger,
    )
    val result = retCode == 0
    if (!result) {
      scribe.warn("There were issues stopping the Bloop server.")
      scribe.warn(
        "If it doesn't start back up you can run the `build-restart` command manually."
      )
    }
    result
  }

  def newServer(
      bspTraceRoot: AbsolutePath,
      userConfiguration: UserConfiguration,
      bspStatusOpt: Option[ConnectionBspStatus],
  ): Future[BuildServerConnection] = {
    val bloopVersionOpt = userConfiguration.bloopVersion
    BuildServerConnection
      .fromSockets(
        bspTraceRoot,
        projectRoot,
        client,
        languageClient,
        () => connect(bloopVersionOpt, userConfiguration),
        tables.dismissedNotifications.ReconnectBsp,
        tables.dismissedNotifications.RequestTimeout,
        serverConfig,
        name,
        bspStatusOpt,
        workDoneProgress = workDoneProgress,
      )
      .recover { case NonFatal(e) =>
        Try(
          // Bloop output
          BloopServers.bloopDaemonDir.resolve("output").readText
        ).foreach {
          scribe.error(_)
        }
        throw e
      }
  }

  /**
   * Ensure Bloop is running the intended version that the user has passed
   * in via UserConfiguration. If not, shut Bloop down and reconnect to it.
   *
   * @param expectedVersion desired version that the user has passed in. This
   *                        could either be a newly passed in version from the
   *                        user or the default Bloop version.
   * @param runningVersion  the current running version of Bloop.
   * @param userDefinedNew  whether or not the user has defined a new version.
   * @param userDefinedOld  whether or not the user has the version running
   *                        defined or if they are just running the default.
   * @param reconnect       function to connect back to the build server.
   */
  def ensureDesiredVersion(
      expectedVersion: String,
      runningVersion: String,
      userDefinedNew: Boolean,
      userDefinedOld: Boolean,
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    val correctVersionRunning = expectedVersion == runningVersion
    val changedToNoVersion = userDefinedOld && !userDefinedNew
    val versionChanged = userDefinedNew && !correctVersionRunning
    val versionRevertedToDefault = changedToNoVersion && !correctVersionRunning

    if (versionRevertedToDefault || versionChanged) {
      languageClient
        .showMessageRequest(
          Messages.BloopVersionChange.params()
        )
        .asScala
        .flatMap {
          case item if item == Messages.BloopVersionChange.reconnect =>
            shutdownServer()
            reconnect().ignoreValue
          case _ =>
            Future.unit
        }
    } else {
      Future.unit
    }
  }

  def checkPropertiesChanged(
      old: UserConfiguration,
      newConfig: UserConfiguration,
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    if (old.bloopJvmProperties != newConfig.bloopJvmProperties) {
      languageClient
        .showMessageRequest(
          Messages.BloopJvmPropertiesChange.params()
        )
        .asScala
        .flatMap {
          case item if item == Messages.BloopJvmPropertiesChange.reconnect =>
            shutdownServer()
            reconnect().ignoreValue
          case _ =>
            Future.unit
        }
    } else {
      Future.unit
    }

  }

  private def metalsJavaHome(userConfiguration: UserConfiguration) =
    userConfiguration.javaHome
      .orElse(sys.env.get("JAVA_HOME"))
      .orElse(sys.props.get("java.home"))

  private lazy val bloopLogger: BloopRifleLogger = new BloopRifleLogger {
    def info(msg: => String): Unit = scribe.info(msg)
    def debug(msg: => String, ex: Throwable): Unit = scribe.debug(msg, ex)
    def debug(msg: => String): Unit = scribe.debug(msg)
    def error(msg: => String): Unit = scribe.error(msg)
    def error(msg: => String, ex: Throwable): Unit = scribe.error(msg, ex)

    private def loggingOutputStream(log: String => Unit): OutputStream = {
      new OutputStream {
        private val buf = new ByteArrayOutputStream
        private val lock = new Object
        private def check(): Unit = {
          lock.synchronized {
            val b = buf.toByteArray
            val s = new String(b)
            val idx = s.lastIndexOf("\n")
            if (idx >= 0) {
              s.take(idx + 1).split("\r?\n").foreach(log)
              buf.reset()
              buf.write(s.drop(idx + 1).getBytes)
            }
          }
        }
        def write(b: Int) =
          lock.synchronized {
            buf.write(b)
            if (b == '\n')
              check()
          }
        override def write(b: Array[Byte]) =
          lock.synchronized {
            buf.write(b)
            if (b.exists(_ == '\n')) check()
          }
        override def write(b: Array[Byte], off: Int, len: Int) =
          lock.synchronized {
            buf.write(b, off, len)
            if (b.iterator.drop(off).take(len).exists(_ == '\n')) check()
          }
      }
    }
    def bloopBspStdout: Some[java.io.OutputStream] = Some(
      loggingOutputStream(scribe.debug(_))
    )
    def bloopBspStderr: Some[java.io.OutputStream] = Some(
      loggingOutputStream(scribe.info(_))
    )
    def bloopCliInheritStdout = false
    def bloopCliInheritStderr = false
  }

  /* Added after 1.3.4, we can probably remove this in a future version.
   */
  private def checkOldBloopRunning(): Future[Unit] = try {
    metalsJavaHome.flatMap { home =>
      ShellRunner
        .runSync(
          List(s"${home}/bin/jps", "-l"),
          projectRoot,
          redirectErrorOutput = false,
        )
        .flatMap { processes =>
          "(\\d+) bloop[.]Server".r
            .findFirstMatchIn(processes)
            .map(_.group(1).toInt)
        }
    } match {
      case None => Future.unit
      case Some(value) =>
        languageClient
          .showMessageRequest(
            OldBloopVersionRunning.params()
          )
          .asScala
          .map { res =>
            Option(res) match {
              case Some(item) if item == OldBloopVersionRunning.yes =>
                ShellRunner.runSync(
                  List("kill", "-9", value.toString()),
                  projectRoot,
                  redirectErrorOutput = false,
                )
              case _ =>
            }
          }
    }
  } catch {
    case NonFatal(e) =>
      scribe.warn(
        "Could not check if the deprecated bloop server is still running",
        e,
      )
      Future.unit
  }

  private def bloopConfig(userConfig: Option[UserConfiguration]) = {

    val addr = BloopRifleConfig.Address.DomainSocket(
      serverConfig.bloopDirectory
        .getOrElse(bloopDaemonDir)
        .toNIO
    )

    val config = BloopRifleConfig
      .default(addr, fetchBloop _, projectRoot.toNIO.toFile)
      .copy(
        bspSocketOrPort = Some { () =>
          val pid =
            ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@').toInt
          val dir = bloopWorkingDir.resolve("bsp").toNIO
          if (!Files.exists(dir)) {
            Files.createDirectories(dir.getParent)
            if (Properties.isWin)
              Files.createDirectory(dir)
            else
              Files.createDirectory(
                dir,
                PosixFilePermissions
                  .asFileAttribute(PosixFilePermissions.fromString("rwx------")),
              )
          }
          val socketPath = dir.resolve(s"proc-$pid")
          if (Files.exists(socketPath))
            Files.delete(socketPath)
          BspConnectionAddress.UnixDomainSocket(socketPath.toFile)
        },
        bspStdout = bloopLogger.bloopBspStdout,
        bspStderr = bloopLogger.bloopBspStderr,
      )

    userConfig.flatMap(_.bloopJvmProperties) match {
      case None => config
      case Some(opts) => config.copy(javaOpts = opts)
    }
  }

  private def connect(
      bloopVersionOpt: Option[String],
      userConfiguration: UserConfiguration,
  ): Future[SocketConnection] = {
    val config = bloopConfig(Some(userConfiguration))

    val maybeStartBloop = {

      val running = BloopRifle.check(config, bloopLogger)

      if (running) {
        scribe.info("Found a Bloop server running")
        Future.unit
      } else {
        scribe.info("No running Bloop server found, starting one.")
        val ext = if (Properties.isWin) ".exe" else ""
        val metalsJavaHomeOpt = metalsJavaHome(userConfiguration)
        val javaCommand = metalsJavaHomeOpt match {
          case Some(metalsJavaHome) =>
            Paths.get(metalsJavaHome).resolve(s"bin/java$ext").toString
          case None => "java"
        }
        val version = bloopVersionOpt.getOrElse(defaultBloopVersion)
        checkOldBloopRunning().flatMap { _ =>
          BloopRifle.startServer(
            config,
            sh,
            bloopLogger,
            version,
            javaCommand,
          )
        }
      }
    }

    def openConnection(
        conn: BspConnection,
        period: FiniteDuration,
        timeout: FiniteDuration,
    ): Socket = {

      @tailrec
      def create(stopAt: Long): Socket = {
        val maybeSocket =
          try Right(conn.openSocket(period, timeout))
          catch {
            case e: ConnectException => Left(e)
          }
        maybeSocket match {
          case Right(socket) => socket
          case Left(e) =>
            if (System.currentTimeMillis() >= stopAt)
              throw new IOException(s"Can't connect to ${conn.address}", e)
            else {
              Thread.sleep(period.toMillis)
              create(stopAt)
            }
        }
      }

      create(System.currentTimeMillis() + timeout.toMillis)
    }

    def openBspConn = Future {

      val conn = BloopRifle.bsp(
        config,
        projectRoot.toNIO,
        bloopLogger,
      )

      val finished = Promise[Unit]()
      conn.closed.ignoreValue.onComplete(finished.tryComplete)

      val socket = openConnection(conn, config.period, config.timeout)

      SocketConnection(
        name,
        new ClosableOutputStream(socket.getOutputStream, "Bloop OutputStream"),
        new QuietInputStream(socket.getInputStream, "Bloop InputStream"),
        Nil,
        finished,
      )
    }

    for {
      _ <- maybeStartBloop
      conn <- openBspConn
    } yield conn
  }
}

object BloopServers {
  val name = "Bloop"

  private val bloopDirectories = {
    // Scala CLI is still used since we wanted to avoid breaking thigns
    ProjectDirectories.from(null, null, "ScalaCli")
  }

  lazy val bloopDaemonDir =
    bloopWorkingDir.resolve("daemon")

  lazy val bloopWorkingDir = {
    val baseDir =
      if (Properties.isMac) bloopDirectories.cacheDir
      else bloopDirectories.dataLocalDir
    AbsolutePath(Paths.get(baseDir).resolve("bloop"))
  }

  def fetchBloop(version: String): Either[Throwable, Seq[File]] = {

    val (org, name) = BloopRifleConfig.defaultModule.split(":", -1) match {
      case Array(org0, name0) => (org0, name0)
      case Array(org0, "", name0) =>
        val sbv =
          if (BloopRifleConfig.defaultScalaVersion.startsWith("2."))
            BloopRifleConfig.defaultScalaVersion
              .split('.')
              .take(2)
              .mkString(".")
          else
            BloopRifleConfig.defaultScalaVersion.split('.').head
        (org0, name0 + "_" + sbv)
      case _ =>
        sys.error(
          s"Malformed default Bloop module '${BloopRifleConfig.defaultModule}'"
        )
    }

    try {
      val cp = coursierapi.Fetch
        .create()
        .addDependencies(coursierapi.Dependency.of(org, name, version))
        .fetch()
        .asScala
        .toVector
      Right(cp)
    } catch {
      case NonFatal(t) =>
        Left(t)
    }
  }

  def defaultBloopVersion = BloopRifleConfig.defaultVersion
}
