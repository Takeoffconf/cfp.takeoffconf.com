import sbt._
import Keys._
import play.Project._

/**
 * Build conf file.
 * Import Scala Sedis driver for Redis.
 * @nmartignole
 */
object ApplicationBuild extends Build {

  val appName = "cfp-devoxxfr"
  val appVersion = "1.3-SNAPSHOT"


  val appDependencies = Seq(
    "redis.clients" % "jedis" % "2.1.0"
    , "com.typesafe" %% "play-plugins-mailer" % "2.1.0"
    , "org.apache.commons" % "commons-lang3" % "3.1"
    , "commons-io" % "commons-io" % "2.4"
    , "commons-codec" % "commons-codec" % "1.7" // for new Base64 that has support for String
  )

  libraryDependencies ++= Seq(

  )

  val main = play.Project(appName, appVersion, appDependencies).settings(
    resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
  )

}

