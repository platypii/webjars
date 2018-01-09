package utils


import java.io.{BufferedInputStream, ByteArrayInputStream}
import java.net.URL

import akka.util.Timeout
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import play.api.test._

import scala.concurrent.duration._

class BowerGitHubSpec extends PlaySpecification with GlobalApplication {

  override implicit def defaultAwaitTimeout: Timeout = 300.seconds

  lazy val bowerGitHub: BowerGitHub = application.injector.instanceOf[BowerGitHub]

  "groupId" should {
    "contain the org when given a Bower package name" in {
      await(bowerGitHub.groupId("jQuery")) must beEqualTo ("org.webjars.bowergithub.jquery")
    }
    "be lowercase" in {
      await(bowerGitHub.groupId("https://github.com/PolymerElements/iron-elements")) must beEqualTo ("org.webjars.bowergithub.polymerelements")
    }
    "work with forks" in {
      await(bowerGitHub.groupId("https://github.com/jamesward/iron-elements")) must beEqualTo ("org.webjars.bowergithub.jamesward")
    }
  }

  "artifactId" should {
    "be the repo name" in {
      val url = "https://github.com/PolymerElements/iron-elements"
      await(bowerGitHub.artifactId(url)) must beEqualTo ("iron-elements")
    }
    "be lowercase" in {
      await(bowerGitHub.artifactId("jQuery")) must beEqualTo ("jquery")
    }
    "not contain a .git" in {
      val url = "https://github.com/PolymerElements/iron-elements.git"
      await(bowerGitHub.artifactId(url)) must beEqualTo ("iron-elements")
    }
    "work with forks" in {
      val url = "https://github.com/jamesward/test-iron-elements"
      await(bowerGitHub.artifactId(url)) must beEqualTo ("test-iron-elements")
    }
  }

  "name" should {
    "be the package name" in {
      val gitHubWithUpperCase = await(bowerGitHub.info("https://github.com/PolymerElements/iron-elements"))
      gitHubWithUpperCase.name must beEqualTo ("iron-elements")
    }
    "be the same case as the bower.json" in {
      val justName = await(bowerGitHub.info("jQuery"))
      justName.name must beEqualTo ("jQuery")
    }
  }

  "bowerToMaven" should {
    "work with a plain name and version" in {
      val (group, artifact, version) = await(bowerGitHub.bowerToMaven("jQuery" -> "1.0.0"))
      group must beEqualTo ("org.webjars.bowergithub.jquery")
      artifact must beEqualTo ("jquery")
      version must beEqualTo ("1.0.0")
    }
    "work with a github short reference" in {
      val (group, artifact, version) = await(bowerGitHub.bowerToMaven("jquery/jquery" -> "1.0.0"))
      group must beEqualTo ("org.webjars.bowergithub.jquery")
      artifact must beEqualTo ("jquery")
      version must beEqualTo ("1.0.0")
    }
    "work with a github short reference" in {
      val (group, artifact, version) = await(bowerGitHub.bowerToMaven("jquery" -> "jquery/jquery"))
      group must beEqualTo ("org.webjars.bowergithub.jquery")
      artifact must beEqualTo ("jquery")
      version must beEqualTo ("[0,)")
    }
    "work with a github short reference and version" in {
      val (group, artifact, version) = await(bowerGitHub.bowerToMaven("jquery" -> "jquery/jquery#1.0.0"))
      group must beEqualTo ("org.webjars.bowergithub.jquery")
      artifact must beEqualTo ("jquery")
      version must beEqualTo ("1.0.0")
    }
    "work with a github url" in {
      val (group, artifact, version) = await(bowerGitHub.bowerToMaven("jquery" -> "https://github.com/jquery/jquery"))
      group must beEqualTo ("org.webjars.bowergithub.jquery")
      artifact must beEqualTo ("jquery")
      version must beEqualTo ("[0,)")
    }
    "work with a github url and version" in {
      val (group, artifact, version) = await(bowerGitHub.bowerToMaven("jquery" -> "https://github.com/jquery/jquery#1.0.0"))
      group must beEqualTo ("org.webjars.bowergithub.jquery")
      artifact must beEqualTo ("jquery")
      version must beEqualTo ("1.0.0")
    }
  }

  "dependencies" should {
    "be converted from bower package names" in {
      val dependencies = await(bowerGitHub.mavenDependencies(Map("jQuery" -> "3.2.1")))
      dependencies.head must beEqualTo ("org.webjars.bowergithub.jquery", "jquery", "3.2.1")
    }
    "be converted from github short syntax package names" in {
      val dependencies = await(bowerGitHub.mavenDependencies(Map("iron-validator-behavior" -> "PolymerElements/iron-validator-behavior#^1.0.0")))
      dependencies.head must beEqualTo ("org.webjars.bowergithub.polymerelements", "iron-validator-behavior", "[1.0.0,2)")
    }
    "not have a prepended v in the version" in {
      val dependencies = await(bowerGitHub.mavenDependencies(Map("jQuery" -> "v3.2.1")))
      dependencies.head must beEqualTo ("org.webjars.bowergithub.jquery", "jquery", "3.2.1")
    }
  }

  "version" should {
    "not contain a prepended v" in {
      val bowerInfo = await(bowerGitHub.info("jQuery", Some("v3.2.1")))
      bowerInfo.version must beEqualTo ("3.2.1")
    }
    "should always be a git tag with any prepended v removed" in {
      val gitHubInfo = await(bowerGitHub.info("https://github.com/PolymerElements/iron-elements.git", Some("v1.0.10")))
      gitHubInfo.version must beEqualTo ("1.0.10")
    }
  }

  "contents" should {
    "be in the META-INF/resources/webjars/[BOWER NAME]/[CONTENTS] form" in {
      val name = "jquery"

      val archive = await(bowerGitHub.archive(name, "3.2.1"))

      val excludes = await(bowerGitHub.excludes(name, "3.2.1"))

      val webJar = WebJarCreator.createWebJar(archive, bowerGitHub.contentsInSubdir, excludes, "", "org.webjars.bowergithub.jquery", "jquery", "3.2.1", "jQuery/")

      val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(new ByteArrayInputStream(webJar))

      val names = Stream.continually(archiveStream.getNextEntry).takeWhile(_ != null).map(_.getName).toSet
      names must contain ("META-INF/resources/webjars/jQuery/dist/jquery.js")
    }
    "must exclude ignored files" in {
      val name = "vaadin-grid"

      val archive = await(bowerGitHub.archive(name, "4.0.0-alpha5"))
      val excludes = await(bowerGitHub.excludes(name, "4.0.0-alpha5"))

      val webJar = WebJarCreator.createWebJar(archive, bowerGitHub.contentsInSubdir, excludes, "", "org.webjars.bowergithub.vaadin", "vaadin-grid", "4.0.0-alpha5", "vaadin-grid/")

      val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(new ByteArrayInputStream(webJar))

      val names = Stream.continually(archiveStream.getNextEntry).takeWhile(_ != null).map(_.getName).toSet
      names must contain ("META-INF/resources/webjars/vaadin-grid/index.html")
      names must not contain "META-INF/resources/webjars/vaadin-grid/.npmignore"
    }
  }

  "archive" should {
    "work with a v in front of the version" in {
      val inputStream = await(bowerGitHub.archive("vaadin-grid", "v4.0.0-alpha5"))
      val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(inputStream))

      Stream.continually(archiveStream.getNextEntry).takeWhile(_ != null).map(_.getName) must contain (".npmignore")
    }
    "work when using a bower name" in {
      val inputStream = await(bowerGitHub.archive("vaadin-grid", "4.0.0-alpha5"))
      val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(inputStream))

      Stream.continually(archiveStream.getNextEntry).takeWhile(_ != null).map(_.getName) must contain (".npmignore")
    }
    "work when using a github short url" in {
      val inputStream = await(bowerGitHub.archive("vaadin/vaadin-grid", "4.0.0-alpha5"))
      val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(inputStream))

      Stream.continually(archiveStream.getNextEntry).takeWhile(_ != null).map(_.getName) must contain (".npmignore")
    }
  }

  "excludes" should {
    "contain contents of the ignore section from bower.json" in {
      val excludes = await(bowerGitHub.excludes("vaadin-grid", "4.0.0-alpha5"))
      excludes must contain ("**/.*")
    }
  }

}
