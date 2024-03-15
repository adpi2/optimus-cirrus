/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package optimus.stratosphere.updater

import optimus.stratosphere.artifactory.ArtifactoryToolDownloader
import optimus.stratosphere.bootstrap.GitProcess
import optimus.stratosphere.bootstrap.OsSpecific
import optimus.stratosphere.bootstrap.StratosphereException
import optimus.stratosphere.common.SemanticVersion
import optimus.stratosphere.config.StratoWorkspaceCommon
import optimus.stratosphere.filesanddirs.PathsOpts._
import optimus.stratosphere.utils.CommonProcess
import optimus.stratosphere.utils.ConfigUtils

import java.nio.file.Path
import java.nio.file.Paths
import java.util
import scala.util.control.NonFatal
import scala.util.matching.Regex

object GitUpdater {
  def gitVersion(stratoWorkspace: StratoWorkspaceCommon): Option[SemanticVersion] = {
    if (!stratoWorkspace.hasGitInWorkspace) {
      None
    } else {
      Some(
        SemanticVersion.parse(
          new CommonProcess(stratoWorkspace)
            .runGit(stratoWorkspace.directoryStructure.sourcesDirectory)("--version")
            .stripPrefix("git version ")
            .trim
        ))
    }
  }

  val NamePattern: Regex = """(?s).*Name:\s+([^\n\r]+)[\r\n]+.*""".r
  val ConfigPattern: Regex = """(\S+)\s+(\S+)""".r
  val UserInURLPattern: Regex = """http://\w+@\w.*""".r

}

class GitUpdater(stratoWorkspace: StratoWorkspaceCommon) {
  import GitUpdater._
  private val overwrittenHookNames = List("pre-commit", "prepare-commit-msg", "pre-push")
  private val bitbucketInstances = stratoWorkspace.internal.urls.bitbucket.all.values.toSeq

  def configureRepo(): Unit = {
    adjustSettings()
    applyFetchConfig()
    copyHooks()
  }

  private def runGitIgnoreExit(args: String*): String = {
    new CommonProcess(stratoWorkspace)
      .runGit(srcDir = stratoWorkspace.directoryStructure.sourcesDirectory, ignoreExitCode = true)(args: _*)
  }
  private def runGit(args: String*): String = {
    new CommonProcess(stratoWorkspace)
      .runGit(srcDir = stratoWorkspace.directoryStructure.sourcesDirectory, ignoreExitCode = false)(args: _*)
  }

  private def getGitConfig(regexp: String): Seq[String] = {
    runGitIgnoreExit("config", "--get-regexp", regexp).linesIterator.toSeq
  }

  private def getAndModifyGitConfig(regexp: String)(
      mapper: (String, String) => (String, String),
      filter: (String, String) => Boolean = (_, _) => true): Unit = {
    val result = getGitConfig(regexp).collect {
      case ConfigPattern(configKey, configValue)
          if bitbucketInstances.exists(i => configValue.contains(i)) && filter(configKey, configValue) =>
        mapper(configKey, configValue)
    }
    result.foreach { case (key, value) => runGit("config", key, value) }
  }

  private def configValueContainsUsername(key: String, value: String): Boolean = {
    UserInURLPattern.findFirstIn(value).isDefined
  }

  def reportResolutionFailures(value: Map[String, String]): Unit = {
    val missing = bitbucketInstances.filterNot(value.contains)
    if (missing.nonEmpty) {
      stratoWorkspace.log.warning(s"""Could not resolve the following stash instances to host names:
                                     |${missing.mkString("\n")}""".stripMargin)
    }
    // TODO (OPTIMUS-62963): emit crumb on failure w/ details
  }

  def doUpdateFetchSettings(): Unit = {
    stratoWorkspace.log.info("Updating Bitbucket host mappings...")
    removeInsteadOfs()
    val resolved: Map[String, String] = bitbucketInstances.collect { instance =>
      new CommonProcess(stratoWorkspace).runAndWaitFor(Seq("nslookup", instance)) match {
        case NamePattern(name) => (instance, name)
      }
    }.toMap
    stratoWorkspace.log.debug(s"""resolved stash instances to names: ${resolved.toString}""")
    reportResolutionFailures(resolved)
    val configureRepositoryCommands: Seq[Seq[String]] = resolved.map { case (instance, name) =>
      Seq("config", s"""url."http://${name}".insteadOf""", s"""http://${instance}""")
    }.toSeq
    configureRepositoryCommands.foreach { args => runGit(args.toSeq: _*) }
    // emptyAuth
    runGit("config", "http.emptyAuth", "true")
  }

  def removeInsteadOfs(): Unit = getAndModifyGitConfig("url\\..*\\.insteadOf") { (configKey, configValue) =>
    ("--unset", configKey)
  }

  def doRevertToOldFetchConfig(): Unit = {
    // unset all insteadOf settings for instances we care about
    removeInsteadOfs()
    // emptyAuth
    runGitIgnoreExit("config", "--unset", "http.emptyAuth")
  }

  def doAddUserNamesToBitbucketRemotes(): Unit = {
    def addUsernameToRemoteUrl(configKey: String, configValue: String): (String, String) = {
      (configKey, configValue.replaceFirst(s"""http://""", s"""http://${stratoWorkspace.userName}@"""))
    }
    getAndModifyGitConfig("remote\\..+\\.url")(
      mapper = addUsernameToRemoteUrl,
      filter = !configValueContainsUsername(_, _) // only add usernames to remotes that don't already have them
    )
  }

  def doRemoveUserPrefixFromFetchUrl(): Unit = {
    def removeUsernameFromRemoteUrl(configKey: String, configValue: String): (String, String) = {
      (configKey, configValue.replaceFirst(s"""http://\\w+@""", "http://"))
    }
    getAndModifyGitConfig("remote\\..+\\.url")(
      mapper = removeUsernameFromRemoteUrl,
      filter = configValueContainsUsername // only remove usernames from remotes that have them
    )
  }

  def applyFetchConfig(): Unit = {
    if (OsSpecific.isWindows) {
      if (stratoWorkspace.git.useUpdatedFetchSettings) {
        doUpdateFetchSettings()
        doRemoveUserPrefixFromFetchUrl()
      } else {
        doRevertToOldFetchConfig()
        doAddUserNamesToBitbucketRemotes()
      }
    }
  }

  def ensureGitInstalled(useGitFromArtifactory: Boolean): Unit =
    if (OsSpecific.isWindows && !OsSpecific.isCi) {
      if (stratoWorkspace.tools.git.isDefault != useGitFromArtifactory) {
        stratoWorkspace.log.info("Updating git settings...")
        setGitVersionInConfig(useGitFromArtifactory)
      }

      val gitProcess = new GitProcess(stratoWorkspace.config)
      val gitPath = gitProcess.getGitPath

      if (useGitFromArtifactory && !Paths.get(gitPath).exists()) {
        val downloader = new ArtifactoryToolDownloader(stratoWorkspace, name = "git")
        if (!downloader.install(forceReinstall = false))
          throw new StratosphereException("Could not install git: please see the logs above for more information")
      }

      val stratoPathFile = sys.env.get("STRATO_PATH_FILE")
      stratoPathFile.map(Paths.get(_)).foreach { path =>
        val envCopy = new util.HashMap[String, String](System.getenv())
        val pathEntry = gitProcess.addGitToPath(envCopy)
        path.file.deleteIfExists()
        path.file.write(s"set ${pathEntry.getKey}=${pathEntry.getValue}")
      }
    }

  private def setGitVersionInConfig(useGitFromArtifactory: Boolean): Unit = {
    val customConf = stratoWorkspace.directoryStructure.customConfFile
    ConfigUtils.updateConfigProperty(customConf)("tools.git.isDefault", useGitFromArtifactory)(stratoWorkspace)
    stratoWorkspace.reload()
  }

  private def adjustSettings(): Unit = {
    stratoWorkspace.log.info("Configuring git aliases...")
    val configFileName = "strato.config"
    val blameIgnoreRevsFileName = "git-blame-ignore-revs"
    try {
      // populate if missing
      Seq(configFileName, blameIgnoreRevsFileName).foreach { file =>
        val sourceFile = s"/git/$file"
        if (getClass.getResource(sourceFile) != null) {
          val destinationFile = stratoWorkspace.directoryStructure.gitDirectory.resolve(file)
          destinationFile.file.createFromResourceOrFile(sourceFile)
        }
      }

      val configureRepositoryCommands = Seq(
        Seq("remote", "set-branches", "origin", "*"),
        Seq("config", "--local", "include.path", configFileName),
        Seq("config", "--local", "remote.origin.tagopt", "--no-tags"),
        Seq("config", "--local", "blame.ignoreRevsFile", Path.of(".git", blameIgnoreRevsFileName).toString)
      )

      configureRepositoryCommands.foreach { args => runGit(args.toSeq: _*) }
    } catch {
      case NonFatal(e) =>
        stratoWorkspace.log.warning(
          s"""Failed to configure git aliases. Full stack trace can be found in the log file.
             |If the message above contains a cygwin error, please reboot your machine.
             |If that does not help, please contact ${stratoWorkspace.internal.helpMailGroup}.""".stripMargin
        )
        stratoWorkspace.log.debug(e.toString)

    }
  }

  private def copyHooks(): Unit = {
    if (stratoWorkspace.git.copyHooks) {
      try {
        val hooksDestinationDir = stratoWorkspace.directoryStructure.gitHooksDirectory
        hooksDestinationDir.dir.create()
        if (!hooksDestinationDir.exists()) {
          stratoWorkspace.log.error(s"Git hooks not accessible at '$hooksDestinationDir'")
        }
        stratoWorkspace.log.info("Copying new git hooks to local disk...")
        overwrittenHookNames.foreach(overwriteHook(hooksDestinationDir))
      } catch {
        case NonFatal(exception) =>
          stratoWorkspace.log.error(s"Git hooks were not copied:\n $exception")
      }
    }
  }

  private def overwriteHook(hooksDestinationDir: Path)(hookName: String): Unit = {
    val destinationFile = hooksDestinationDir.resolve(hookName)
    destinationFile.deleteIfExists()
    val sourceFile = s"/git/hooks/$hookName"
    if (getClass.getResource(sourceFile) != null) {
      val bindings = Map(
        "@JAVA_PROJECT@" -> stratoWorkspace.javaProject,
        "@JAVA_VERSION@" -> stratoWorkspace.javaVersion
      )
      destinationFile.file.createFromResourceOrFile(sourceFile, bindings)
      destinationFile.file.makeExecutable()
    }
  }
}
