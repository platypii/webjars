package utils

import java.io.{FileNotFoundException, InputStream}
import java.net.URI

import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.{Done, NotUsed}
import javax.inject.Inject
import models.WebJarType
import play.api.Configuration
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Futures

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Try


class DeployWebJar @Inject()(binTray: BinTray, mavenCentral: MavenCentral, licenseDetector: LicenseDetector, sourceLocator: SourceLocator, configuration: Configuration, heroku: Heroku)(implicit ec: ExecutionContext, futures: Futures, materializer: Materializer) {

  val fork = configuration.getOptional[Boolean]("deploy.fork").getOrElse(false)
  val binTraySubject = "webjars"
  val binTrayRepo = "maven"

  def licenses(packageInfo: PackageInfo, version: String, maybeLicense: Option[String], deployable: Deployable): Future[Map[String, String]] = {
    maybeLicense.fold {
      licenseDetector.resolveLicenses(deployable, packageInfo, Some(version))
    } { license =>
      Future.successful(license.split(",")).map(_.toSet)
    } map LicenseDetector.defaultUrls
  }

  def forkDeploy(deployable: Deployable, nameOrUrlish: String, upstreamVersion: String, deployDependencies: Boolean, preventFork: Boolean): Source[String, Future[NotUsed]] = {
    val app = configuration.get[String]("deploy.herokuapp")
    val cmd = s"deploy ${WebJarType.toString(deployable)} $nameOrUrlish $upstreamVersion $deployDependencies $preventFork"
    heroku.dynoCreate(app, cmd, "Standard-2X")
  }

  def localDeploy(deployable: Deployable, nameOrUrlish: String, upstreamVersion: String, deployDependencies: Boolean, maybeReleaseVersion: Option[String] = None, maybeSourceUri: Option[URI] = None, maybeLicense: Option[String] = None, forceDeploy: Boolean = false): Source[String, Future[NotUsed]] = {

    def webJarNotYetDeployed(groupId: String, artifactId: String, version: String): Future[Unit] = {
      if (!forceDeploy) {
        mavenCentral.fetchPom(groupId, artifactId, version, Some("https://oss.sonatype.org/content/repositories/releases")).flatMap { _ =>
          Future.failed(new IllegalStateException(s"WebJar $groupId $artifactId $version has already been deployed"))
        } recoverWith {
          case _: FileNotFoundException =>
            Future.unit
        }
      }
      else {
        Future.unit
      }
    }

    // todo: map on offer
    def doDeployDependencies(queue: SourceQueueWithComplete[String], packageInfo: PackageInfo): Future[Done] = {
      if (deployDependencies) {
        queue.offer("Determining dependency graph")

        deployable.depGraph(packageInfo).flatMap { depGraph =>

          val deployDepGraphMessage = if (depGraph.isEmpty) {
            "No dependencies."
          }
          else {
            "Deploying these dependencies: " + depGraph.map(dep => dep._1 + "#" + dep._2).mkString(" ")
          }

          queue.offer(deployDepGraphMessage)

          def deployDep(deps: Map[String, String]): Future[Done] = {
            if (deps.isEmpty) {
              Future.successful(Done)
            }
            else {
              val (nameish, version) = deps.head
              deploy(deployable, nameish, version, false, false).runForeach(queue.offer).recover {
                // ignore failures
                case e =>
                  queue.offer(e.getMessage)
                  Done
              } flatMap { _ =>
                deployDep(deps.tail)
              }
            }
          }

          deployDep(depGraph)
        }
      }
      else {
        Future.successful(Done)
      }
    }

    Source.queue[String](Int.MaxValue, OverflowStrategy.backpressure).mapMaterializedValue { queue =>
      val future = for {
        packageInfo <- deployable.info(nameOrUrlish, Some(upstreamVersion), maybeSourceUri)
        groupId <- deployable.groupId(nameOrUrlish, upstreamVersion)
        artifactId <- deployable.artifactId(nameOrUrlish, upstreamVersion)
        mavenBaseDir = groupId.replace(".", "/")

        releaseVersion = deployable.releaseVersion(maybeReleaseVersion, packageInfo)

        _ <- queue.offer(s"Got package info for $groupId $artifactId $releaseVersion")

        _ <- doDeployDependencies(queue, packageInfo)

        _ <- webJarNotYetDeployed(groupId, artifactId, releaseVersion)

        _ <- queue.offer(s"Resolving licenses & dependencies for $groupId $artifactId $releaseVersion")

        licenses <- licenses(packageInfo, upstreamVersion, maybeLicense, deployable)
        _ <- queue.offer(s"Resolved Licenses: ${licenses.keySet.mkString(",")}")

        mavenDependencies <- deployable.mavenDependencies(packageInfo.dependencies)
        _ <- queue.offer("Converted dependencies to Maven")

        optionalMavenDependencies <- deployable.mavenDependencies(packageInfo.optionalDependencies)
        _ <- queue.offer("Converted optional dependencies to Maven")

        sourceUrl <- sourceLocator.sourceUrl(packageInfo.sourceConnectionUri)
        _ <- queue.offer(s"Got the source URL: $sourceUrl")

        pom = templates.xml.pom(groupId, artifactId, releaseVersion, packageInfo, sourceUrl, mavenDependencies, optionalMavenDependencies, licenses).toString()
        _ <- queue.offer("Generated POM")

        zip <- deployable.archive(nameOrUrlish, upstreamVersion)
        _ <- queue.offer(s"Fetched ${deployable.name} zip")

        excludes <- deployable.excludes(nameOrUrlish, upstreamVersion)

        pathPrefix <- deployable.pathPrefix(nameOrUrlish, releaseVersion, packageInfo)

        jar = WebJarCreator.createWebJar(zip, deployable.contentsInSubdir, excludes, pom, groupId, artifactId, releaseVersion, pathPrefix)

        _ <- queue.offer(s"Created ${deployable.name} WebJar")

        packageName = s"$groupId:$artifactId"

        _ <- binTray.getOrCreatePackage(binTraySubject, binTrayRepo, packageName, s"WebJar for $artifactId", Seq("webjar", artifactId), licenses.keySet, packageInfo.sourceConnectionUri, packageInfo.maybeHomepageUrl, packageInfo.maybeIssuesUrl, packageInfo.maybeGitHubOrgRepo)
        _ <- queue.offer("Created BinTray Package")

        _ <- binTray.createOrOverwriteVersion(binTraySubject, binTrayRepo, packageName, releaseVersion, s"$artifactId WebJar release $releaseVersion", Some(s"v$releaseVersion"))
        _ <- queue.offer("Created BinTray Version")

        _ <- binTray.uploadMavenArtifact(binTraySubject, binTrayRepo, packageName, s"$mavenBaseDir/$artifactId/$releaseVersion/$artifactId-$releaseVersion.pom", pom.getBytes)
        _ <- binTray.uploadMavenArtifact(binTraySubject, binTrayRepo, packageName, s"$mavenBaseDir/$artifactId/$releaseVersion/$artifactId-$releaseVersion.jar", jar)
        emptyJar = WebJarCreator.emptyJar()
        _ <- binTray.uploadMavenArtifact(binTraySubject, binTrayRepo, packageName, s"$mavenBaseDir/$artifactId/$releaseVersion/$artifactId-$releaseVersion-sources.jar", emptyJar)
        _ <- binTray.uploadMavenArtifact(binTraySubject, binTrayRepo, packageName, s"$mavenBaseDir/$artifactId/$releaseVersion/$artifactId-$releaseVersion-javadoc.jar", emptyJar)
        _ <- queue.offer("Published BinTray Assets")

        _ <- binTray.signVersion(binTraySubject, binTrayRepo, packageName, releaseVersion)
        _ <- queue.offer("Signed BinTray Assets")

        _ <- binTray.publishVersion(binTraySubject, binTrayRepo, packageName, releaseVersion)
        _ <- queue.offer("Published BinTray Version")

        _ <- queue.offer("Syncing to Maven Central (this could take a while)")

        _ <- binTray.syncToMavenCentral(binTraySubject, binTrayRepo, packageName, releaseVersion)
        _ <- queue.offer("Synced With Maven Central")

        _ <- queue.offer(s"""Deployed!
                          |It will take a few hours for the Maven Central index to update but you should be able to start using the ${deployable.name} WebJar shortly.
                          |GroupID = $groupId
                          |ArtifactID = $artifactId
                          |Version = $releaseVersion
            """.stripMargin)
      } yield NotUsed

      future.onComplete { t =>
        t.fold(queue.fail, _ => queue.complete())
      }

      future
    }
  }

  def deploy(deployable: Deployable, nameOrUrlish: String, upstreamVersion: String, deployDependencies: Boolean, preventFork: Boolean, maybeReleaseVersion: Option[String] = None, maybeSourceUri: Option[URI] = None, maybeLicense: Option[String] = None): Source[String, Future[NotUsed]] = {
    if (fork && !preventFork) {
      forkDeploy(deployable, nameOrUrlish, upstreamVersion, deployDependencies, true)
    }
    else {
      localDeploy(deployable, nameOrUrlish, upstreamVersion, deployDependencies, maybeReleaseVersion, maybeSourceUri, maybeLicense)
    }
  }

  def create(deployable: Deployable, nameOrUrlish: String, upstreamVersion: String, licenseOverride: Option[Map[String, String]], groupIdOverride: Option[String]): Future[(String, Array[Byte])] = {
    import deployable._

    for {
      packageInfo <- deployable.info(nameOrUrlish, Some(upstreamVersion))
      groupId <- groupIdOverride.map(Future.successful).getOrElse(deployable.groupId(nameOrUrlish, upstreamVersion))
      artifactId <- deployable.artifactId(nameOrUrlish, upstreamVersion)

      releaseVersion = upstreamVersion.vless

      licenses <- licenseOverride.fold(licenses(packageInfo, upstreamVersion, None, deployable))(Future.successful)

      mavenDependencies <- deployable.mavenDependencies(packageInfo.dependencies)

      optionalMavenDependencies <- deployable.mavenDependencies(packageInfo.optionalDependencies)

      sourceUrl <- sourceLocator.sourceUrl(packageInfo.sourceConnectionUri)

      pom = templates.xml.pom(groupId, artifactId, releaseVersion, packageInfo, sourceUrl, mavenDependencies, optionalMavenDependencies, licenses).toString()

      zip <- deployable.archive(nameOrUrlish, upstreamVersion)

      excludes <- deployable.excludes(nameOrUrlish, upstreamVersion)

      pathPrefix <- deployable.pathPrefix(nameOrUrlish, releaseVersion, packageInfo)
    } yield artifactId -> WebJarCreator.createWebJar(zip, deployable.contentsInSubdir, excludes, pom, groupId, artifactId, releaseVersion, pathPrefix)
  }

}

object DeployWebJar extends App {

  val (webJarType, nameOrUrlish, upstreamVersion, deployDependencies, preventFork, maybeReleaseVersion, maybeSourceUri, maybeLicense) = if (args.length < 5) {
    val webJarType = StdIn.readLine("WebJar Type: ")
    val nameOrUrlish = StdIn.readLine("Name or URL: ")
    val upstreamVersion = StdIn.readLine("Upstream Version: ")
    val deployDependenciesIn = StdIn.readLine("Deploy dependencies (false|true): ")
    val preventForkIn = StdIn.readLine("Prevent Fork (false|true): ")
    val releaseVersionIn = StdIn.readLine("Release Version (override): ")
    val sourceUriIn = StdIn.readLine("Source URI (override): ")
    val licenseIn = StdIn.readLine("License (override): ")

    val deployDependencies = if (deployDependenciesIn.isEmpty) false else deployDependenciesIn.toBoolean

    val preventFork = if (preventForkIn.isEmpty) false else preventForkIn.toBoolean

    val maybeReleaseVersion = if (releaseVersionIn.isEmpty) None else Some(releaseVersionIn)

    val maybeSourceUri = if (sourceUriIn.isEmpty) None else Try(new URI(sourceUriIn)).toOption

    val maybeLicense = if (licenseIn.isEmpty) None else Some(licenseIn)

    (webJarType, nameOrUrlish, upstreamVersion, deployDependencies, preventFork, maybeReleaseVersion, maybeSourceUri, maybeLicense)
  }
  else {
    // todo: come up with a way to handle the optional params because if we fork then we lose them
    (args(0), args(1), args(2), args(3).toBoolean, args(4).toBoolean, None, None, None)
  }

  if (nameOrUrlish.isEmpty || upstreamVersion.isEmpty) {
    println("Name and version must be specified")
    sys.exit(1)
  }
  else {
    val app = new GuiceApplicationBuilder().build()

    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
    implicit val materializer: Materializer = app.injector.instanceOf[Materializer]

    val deployWebJar = app.injector.instanceOf[DeployWebJar]

    val npm = app.injector.instanceOf[NPM]
    val bower = app.injector.instanceOf[Bower]
    val bowerGitHub = app.injector.instanceOf[BowerGitHub]

    val allDeployables = Set(npm, bower, bowerGitHub)


    val deployFuture = WebJarType.fromString(webJarType, allDeployables).fold[Future[Done]] {
      Future.failed(new Exception(s"Specified WebJar type '$webJarType' can not be deployed"))
    } { deployable =>
      deployWebJar.deploy(deployable, nameOrUrlish, upstreamVersion, deployDependencies, preventFork, maybeReleaseVersion, maybeSourceUri, maybeLicense).runForeach(println)
    }

    deployFuture.failed.foreach(e => println(e.getMessage))
    deployFuture.onComplete(_ => app.stop())
  }

}

trait Deployable extends WebJarType {

  implicit class RichString(val s: String) {
    def vless: String = s.stripPrefix("v").replace("^v", "^").replace("~v", "v")
    def vwith: String = if (s.startsWith("v")) s else "v" + s
  }

  def groupId(nameOrUrlish: String, version: String): Future[String]
  def artifactId(nameOrUrlish: String, version: String): Future[String]
  def releaseVersion(maybeVersion: Option[String], packageInfo: PackageInfo): String = maybeVersion.getOrElse(packageInfo.version).vless
  def excludes(nameOrUrlish: String, version: String): Future[Set[String]]
  val metadataFile: String
  val contentsInSubdir: Boolean
  def pathPrefix(nameOrUrlish: String, releaseVersion: String, packageInfo: PackageInfo): Future[String]
  def info(nameOrUrlish: String, maybeVersion: Option[String], maybeSourceUri: Option[URI] = None): Future[PackageInfo]
  def mavenDependencies(dependencies: Map[String, String]): Future[Set[(String, String, String)]]
  def archive(nameOrUrlish: String, version: String): Future[InputStream]
  def versions(nameOrUrlish: String): Future[Set[String]]

  def parseDep(nameAndVersionish: (String, String)): (String, String) = {
    val (name, versionish) = nameAndVersionish

    if (versionish.contains("/")) {
      val urlish = versionish.takeWhile(_ != '#')
      val version = versionish.stripPrefix(urlish).stripPrefix("#").vless

      urlish -> version
    }
    else {
      name -> versionish.vless
    }
  }

  def depGraph(packageInfo: PackageInfo, deps: Map[String, String] = Map.empty[String, String])(implicit ec: ExecutionContext, futures: Futures): Future[Map[String, String]]

}
