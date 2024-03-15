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
package optimus.buildtool.dependencies

import com.typesafe.config._
import optimus.buildtool.config._
import optimus.buildtool.files.Asset
import optimus.buildtool.format.MavenDependenciesConfig
import optimus.buildtool.format.BuildDependenciesConfig
import optimus.buildtool.format.ConfigUtils._
import optimus.buildtool.format.DependenciesConfig
import optimus.buildtool.format.Error
import optimus.buildtool.format.Failure
import optimus.buildtool.format.Keys
import optimus.buildtool.format.Message
import optimus.buildtool.format.JvmDependenciesConfig
import optimus.buildtool.format.MavenDefinition.loadMavenDefinition
import optimus.buildtool.format.ObtFile
import optimus.buildtool.format.OrderingUtils
import optimus.buildtool.format.ProjectProperties
import optimus.buildtool.format.ResolverDefinition
import optimus.buildtool.format.ResolverDefinition.ResolverType
import optimus.buildtool.format.Result
import optimus.buildtool.format.ResultSeq
import optimus.buildtool.format.Success
import optimus.buildtool.format.SuccessSeq
import optimus.buildtool.format.TopLevelConfig

import java.nio.file.FileSystems
import scala.collection.compat._
import scala.collection.immutable.Seq
import scala.jdk.CollectionConverters._

object JvmDependenciesLoader {
  private[buildtool] val Artifacts = "artifacts"
  private[buildtool] val Configuration = "configuration"
  private[buildtool] val Configurations = "configurations"
  private[buildtool] val IvyConfigurations = "ivyConfigurations"
  private[buildtool] val ContainsMacros = "containsMacros"
  private[buildtool] val Classifier = "classifier"
  private[buildtool] val Dependencies = "dependencies"
  private[buildtool] val Excludes = "excludes"
  private[buildtool] val ExtraLibs = "extraLibs"
  private[buildtool] val Force = "force"
  private[buildtool] val Group = "group"
  private[buildtool] val Groups = "groups"
  private[buildtool] val IsScalacPlugin = "isScalacPlugin"
  private[buildtool] val Version = "version"
  private[buildtool] val Variants = "variants"
  private[buildtool] val Name = "name"
  private[buildtool] val NativeDependencies = "nativeDependencies"
  private[buildtool] val Resolvers = "resolvers"

  private val Ext = "ext"
  private val KeySuffix = "keySuffix"
  private val Reason = "reason"
  private val TypeStr = "type"
  private val Transitive = "transitive"

  def mavenScalaLibName(name: String, scalaMajorVer: String) = s"${name}_$scalaMajorVer"

  def afsScalaVersion(version: String, scalaMajorVer: String) = s"$scalaMajorVer-$version"

  private[dependencies] def loadLocalDefinitions(
      dependenciesConfig: Config,
      kind: Kind,
      obtFile: ObtFile,
      isMaven: Boolean,
      loadedResolvers: Seq[ResolverDefinition],
      loadedVariantsConfig: Option[Config] = None,
      isExtraLib: Boolean = false,
      scalaMajorVersion: Option[String] = None): Result[Seq[DependencyDefinition]] =
    Result.tryWith(obtFile, dependenciesConfig) {
      val dependencies = for {
        (groupName, groupConfig) <- ResultSeq(dependenciesConfig.nested(obtFile))
        (depName, dependencyConfig) <- ResultSeq(groupConfig.nested(obtFile))
        loadResult = loadLibrary(
          scalaMajorVersion match { // apply scala maven lib name when "scala = true"
            case Some(scalaMajorVer) if isMaven => mavenScalaLibName(depName, scalaMajorVer)
            case _                              => depName
          },
          groupName,
          dependencyConfig,
          kind,
          obtFile,
          isMaven,
          loadedResolvers,
          loadedVariantsConfig,
          isExtraLib,
          scalaMajorVersion
        )
        dep <- ResultSeq(loadResult)
      } yield dep

      dependencies.value.withProblems(deps => OrderingUtils.checkOrderingIn(obtFile, deps))
    }

  def readExcludes(config: Config, file: ObtFile): Result[Seq[Exclude]] = {
    file.tryWith {
      if (config.hasPath(Excludes)) {
        val excludeList = config.getList(Excludes).asScala.toList
        val excludeResults: Seq[Result[Exclude]] = excludeList.map {
          case obj: ConfigObject =>
            val excludeConf: Config = obj.toConfig
            val exclude =
              Exclude(group = excludeConf.optionalString(Group), name = excludeConf.optionalString(Name))

            Success(exclude)
              .withProblems(
                excludeConf.checkExtraProperties(file, Keys.excludesConfig) ++
                  excludeConf.checkEmptyProperties(file, Keys.excludesConfig))
          case other => file.failure(other, s""""$Excludes" element is not an object""")
        }

        Result.sequence(excludeResults)
      } else Success(Nil)
    }
  }

  private def loadLibrary(
      name: String,
      group: String,
      config: Config,
      kind: Kind,
      obtFile: ObtFile,
      isMaven: Boolean,
      loadedResolvers: Seq[ResolverDefinition],
      loadedVariantsConfig: Option[Config] = None,
      isExtraLib: Boolean = false,
      scalaMajorVersion: Option[String] = None): Result[Seq[DependencyDefinition]] =
    Result.tryWith(obtFile, config) {

      def loadArtifacts(config: Config) =
        if (!config.hasPath(Artifacts)) Success(Nil)
        else {
          Result.traverse(config.getObject(Artifacts).toConfig.nested(obtFile)) { case (name, artifactConfig) =>
            Result
              .tryWith(obtFile, artifactConfig) {
                val tpe = artifactConfig.getString(TypeStr)
                val ext = artifactConfig.stringOrDefault(Ext, tpe)
                Success(IvyArtifact(name, tpe, ext))
              }
              .withProblems(artifactConfig.checkExtraProperties(obtFile, Keys.artifactConfig))
          }
        }

      def loadVersion(obtFile: ObtFile, config: Config): Option[String] = {
        val depVersion = // allow no version config for multiSource afs dependencies
          if (!isMaven && obtFile.path == JvmDependenciesConfig.path) config.optionalString(Version)
          else Some(config.getString(Version))
        if (isMaven) depVersion
        else
          depVersion.map { v =>
            scalaMajorVersion match { // special case for AFS lib when scala = true)
              case Some(scalaVer) => afsScalaVersion(v, scalaVer)
              case None           => v
            }
          }
      }

      def loadResolvers(
          obtFile: ObtFile,
          config: Config,
          predefinedResolvers: Option[Seq[String]]): Result[Seq[String]] = Result.tryWith(obtFile, config) {
        val (validNames, invalidNames) = predefinedResolvers match {
          case Some(userDefined) =>
            val (validNames, invalidNames) = userDefined.partition(str => loadedResolvers.exists(r => r.name == str))
            (validNames, invalidNames)
          case None =>
            val preDefinedIvyRepos = loadedResolvers.filter(_.tpe == ResolverType.Ivy)
            val preDefinedMavenRepos = loadedResolvers.filter(_.tpe == ResolverType.Maven)
            if (isMaven && preDefinedMavenRepos.nonEmpty) (preDefinedMavenRepos.map(_.name), Nil)
            else (preDefinedIvyRepos.map(_.name), Nil)
        }
        Success(validNames).withProblems(invalidNames.map(invalid =>
          Error(s"resolver name $invalid is invalid!", obtFile, config.origin().lineNumber())))
      }

      def loadBaseLibrary(fullConfig: Config) = {
        for {
          artifactExcludes <- readExcludes(fullConfig, obtFile)
          artifacts <- loadArtifacts(fullConfig)
          (version, isDisabled) = loadVersion(obtFile, fullConfig) match {
            case Some(ver) => (ver, false)
            case None      => ("", true)
          }
          predefinedResolvers = config.optionalStringList(Resolvers)
          resolvers <- loadResolvers(obtFile, config, predefinedResolvers)
        } yield DependencyDefinition(
          group,
          if (isMaven) fullConfig.stringOrDefault("name", default = name) else name,
          version,
          kind,
          fullConfig.stringOrDefault(Configuration, default = "runtime"),
          config.optionalString(Classifier),
          artifactExcludes,
          None,
          resolvers,
          fullConfig.booleanOrDefault(Transitive, default = true),
          fullConfig.booleanOrDefault(Force, default = false),
          config.origin().lineNumber(),
          fullConfig.stringOrDefault(KeySuffix, default = ""),
          fullConfig.booleanOrDefault(ContainsMacros, default = false),
          fullConfig.booleanOrDefault(IsScalacPlugin, default = false),
          artifacts,
          isMaven = isMaven,
          isDisabled = isDisabled,
          isExtraLib = isExtraLib
        )
      }.withProblems(fullConfig.checkExtraProperties(obtFile, Keys.dependencyDefinition))

      val defaultLib = loadBaseLibrary(config)

      def loadVariant(variant: Config, name: String): Result[DependencyDefinition] = {
        val reason = if (variant.hasPath(Reason)) variant.getString(Reason) else ""
        val variantReason = Variant(name, reason)
        loadBaseLibrary(variant.withFallback(config)).map(_.copy(variant = Some(variantReason)))
      }

      val variantsConfigs =
        loadedVariantsConfig match {
          case Some(multiSourceVariants) =>
            Result.traverse(multiSourceVariants.nested(obtFile)) { case (name, config) =>
              val sourcedConfig = if (isMaven) config.getObject("maven").toConfig else config.getObject("afs").toConfig
              loadVariant(sourcedConfig, name)
            }
          case None =>
            if (!config.hasPath(Variants)) Success(Nil)
            else {
              Result.traverse(config.getObject(Variants).toConfig.nested(obtFile)) { case (name, config) =>
                loadVariant(config, name)
              }
            }
            if (!config.hasPath(Variants)) Success(Nil)
            else
              Result.traverse(config.getObject(Variants).toConfig.nested(obtFile)) { case (name, config) =>
                loadVariant(config, name)
              }
        }
      val configurationVariants =
        if (!config.hasPath(Configurations)) Success(Nil)
        else {
          Result.traverse(config.getStringList(Configurations).asScala.to(Seq)) { config =>
            val variant = Variant(config, "Additional config to use", configurationOnly = true)
            defaultLib.map(_.copy(configuration = config, variant = Some(variant)))
          }
        }

      for {
        dl <- defaultLib
        cv <- configurationVariants
        vc <- variantsConfigs
      } yield Seq(dl) ++ cv ++ vc
    }

  private def loadGroups(
      config: Config,
      dependencies: Iterable[DependencyDefinition],
      obtFile: ObtFile): Result[Seq[DependencyGroup]] = {

    def validateGroup(name: String, values: Seq[String])(groupValue: ConfigValue) = {
      val foundDependencies = values.flatMap(dependencyKey => dependencies.find(_.key == dependencyKey))
      val missingDependencies = values.filterNot(dependencyKey => dependencies.exists(_.key == dependencyKey))
      val problems = missingDependencies.map { missingDep =>
        obtFile.errorAt(groupValue, s"Unknown dependency $missingDep")
      }
      Success(DependencyGroup(name, foundDependencies), problems)
    }

    obtFile.tryWith {
      if (config.hasPath(Groups)) {
        val groupsConfig = config.getObject(Groups).toConfig
        Result.sequence(
          groupsConfig.entrySet.asScala
            .map { entry =>
              validateGroup(entry.getKey, groupsConfig.getStringList(entry.getKey).asScala.to(Seq))(entry.getValue)
            }
            .to(Seq))
      } else Success(Nil)
    }
  }

  private def duplicatesMessages(
      definitions: Seq[DependencyDefinition],
      obtFile: ObtFile): Result[Seq[DependencyDefinition]] = {
    // we only care about one of the messages, the other is exactly the same
    val key = definitions.head.key
    val byNameAndGroup = definitions.groupBy(d => (d.group, d.name))
    val msg = {
      val descriptions =
        byNameAndGroup.keys.map { case (group, name) => s"$Group: '$group' $Name: '$name'" }.mkString("\n")
      s"Detected conflict for key `$key` in:\n$descriptions\n" +
        "Please add `keySuffix=...` to one of problematic dependencies."
    }
    Failure(definitions.map(d => Error(msg, obtFile, d.line)))
  }

  def versionsAsConfig(deps: Iterable[DependencyDefinition]): Config = {
    def versionConfig(dep: DependencyDefinition): ConfigObject =
      ConfigFactory.empty().root().withValue(Version, ConfigValueFactory.fromAnyRef(dep.version))

    def groupConfig(deps: Iterable[DependencyDefinition]): ConfigObject = {
      deps.foldLeft(ConfigFactory.empty().root()) { (root, dep) =>
        root.withValue(dep.name, versionConfig(dep))
      }
    }

    deps
      .groupBy(_.group)
      .foldLeft(ConfigFactory.empty().root()) { case (config, (group, deps)) =>
        config.withValue(group, groupConfig(deps))
      }
      .toConfig
  }

  private def checkForDuplicates(
      definitions: Seq[DependencyDefinition],
      obtFile: ObtFile): Result[Seq[DependencyDefinition]] =
    definitions.size match {
      case 1 => Success(definitions)
      case _ => duplicatesMessages(definitions, obtFile)
    }

  private def loadNativeDeps(root: Config, obtFile: ObtFile): Result[Map[String, NativeDependencyDefinition]] = {
    val nativeDeps = if (root.hasPath(NativeDependencies)) {
      obtFile.tryWith(root.getValue(NativeDependencies)) {
        val config = root.getConfig(NativeDependencies)
        Result
          .traverse(config.keys(obtFile)) { key =>
            val value = config.getValue(key)
            obtFile.tryWith(value) {
              value.valueType match {
                case ConfigValueType.LIST =>
                  Success(
                    key -> NativeDependencyDefinition(
                      line = value.origin.lineNumber,
                      name = key,
                      paths = config.getStringList(key).asScala.to(Seq),
                      extraPaths = Nil
                    ))
                case ConfigValueType.OBJECT =>
                  val depCfg = config.getConfig(key)
                  Success(
                    key -> NativeDependencyDefinition(
                      line = value.origin.lineNumber,
                      name = key,
                      paths = depCfg.stringListOrEmpty("paths"),
                      extraPaths = depCfg.stringListOrEmpty("extraFiles").map(toAsset)
                    )
                  )
                    .withProblems(depCfg.checkExtraProperties(obtFile, Keys.nativeDependencyDefinition))
                case other =>
                  obtFile
                    .failure(value, s"Native dependency definition should be string or object; got '$other'")
              }
            }
          }
          .map(_.toMap)
      }
    } else Success(Map.empty[String, NativeDependencyDefinition])
    nativeDeps.withProblems(ds => OrderingUtils.checkOrderingIn(obtFile, ds.values.to(Seq)))
  }

  private def checkSingleSourceDeps(
      loadedDeps: Seq[DependencyDefinition],
      obtFile: ObtFile): Result[Seq[DependencyDefinition]] = Result.unwrap(for {
    (_, definitions) <- SuccessSeq(loadedDeps.groupBy(_.key).to(Seq))
    checkedDefs <- ResultSeq(checkForDuplicates(definitions, obtFile))
  } yield checkedDefs)

  private def loadExtraLibs(
      depsConfig: Config,
      obtFile: ObtFile,
      isMavenConfig: Boolean,
      loadedResolvers: Seq[ResolverDefinition] = Nil): Result[Seq[DependencyDefinition]] =
    if (depsConfig.hasPath(ExtraLibs)) {
      val extraLibOccurrences = depsConfig.getObject(ExtraLibs).toConfig
      loadLocalDefinitions(
        extraLibOccurrences,
        ExtraLibDefinition,
        obtFile,
        isMavenConfig,
        loadedResolvers = loadedResolvers,
        isExtraLib = true)
    } else Success(Nil)

  private def loadMultiSourceDeps(
      singleSourceDep: Option[JvmDependencies],
      jvmDepsConfig: Config,
      obtFile: ObtFile,
      loadedResolvers: Seq[ResolverDefinition],
      scalaMajorVersion: Option[String]): Result[Option[MultiSourceDependencies]] =
    singleSourceDep match {
      case Some(singleSource) =>
        val multiSourceConfig = jvmDepsConfig.getObject(Dependencies).toConfig
        for {
          multiSourceDeps <- MultiSourceDependenciesLoader.load(
            multiSourceConfig,
            LocalDefinition,
            obtFile,
            loadedResolvers,
            scalaMajorVersion)
          checked <- MultiSourceDependenciesLoader.checkDuplicates(
            obtFile,
            multiSourceDeps,
            singleSource.dependencies ++ singleSource.mavenDependencies)
        } yield Some(checked)
      case None => Success(None)
    }

  private def toAsset(s: String): Asset = Asset(FileSystems.getDefault, s)

  private def resolveFromConfig(
      config: Config,
      obtFile: ObtFile,
      useMavenLibs: Boolean,
      isMavenConfig: Boolean,
      singleSourceDep: Option[JvmDependencies],
      loadedResolvers: Seq[ResolverDefinition],
      scalaMajorVersion: Option[String]
  ): Result[JvmDependencies] = {
    val resolvedConfig = config.resolve()
    for {
      globalExcludes <- readExcludes(config, obtFile)
      localDefinitions <-
        if (singleSourceDep.isDefined) Success(Nil)
        else
          loadLocalDefinitions(
            resolvedConfig.getObject(Dependencies).toConfig,
            LocalDefinition,
            obtFile,
            isMavenConfig,
            loadedResolvers)
      extraLibDefinitions <- loadExtraLibs(resolvedConfig, obtFile, isMavenConfig)
      mavenDefs <- loadMavenDefinition(config, isMavenConfig, useMavenLibs)
      nativeDeps <- loadNativeDeps(resolvedConfig, obtFile)
      all = localDefinitions ++ extraLibDefinitions
      deps <- checkSingleSourceDeps(all, obtFile)
      groups <- loadGroups(config, deps, obtFile)
      multiSourceDependencies <- loadMultiSourceDeps(
        singleSourceDep,
        resolvedConfig,
        obtFile,
        loadedResolvers,
        scalaMajorVersion)
    } yield JvmDependencies(
      dependencies = if (isMavenConfig) Nil else deps,
      mavenDependencies = if (isMavenConfig) deps else Nil,
      multiSourceDependencies = multiSourceDependencies,
      nativeDependencies = nativeDeps,
      groups = groups,
      globalExcludes = globalExcludes,
      mavenDefinition = Option(mavenDefs),
      scalaMajorVersion
    )
  }

  def load(
      centralConfiguration: ProjectProperties,
      loader: ObtFile.Loader,
      useMavenLibs: Boolean,
      scalaMajorVersion: Option[String],
      loadedResolvers: Seq[ResolverDefinition] = Nil): Result[JvmDependencies] = {

    def getCentralDependencies(
        topLevelConfig: TopLevelConfig,
        singleSourceDep: Option[JvmDependencies] = None): Result[JvmDependencies] = {
      def missingMsg(isError: Boolean): Result[JvmDependencies] =
        Success(
          JvmDependencies.empty,
          Seq(Message(s"OBT file missing: ${topLevelConfig.path}", topLevelConfig, isError = isError))
        )

      val (checkingKeys, isMavenConfig) = topLevelConfig match {
        case MavenDependenciesConfig => (Keys.mavenDependenciesFile, true)
        case _                       => (Keys.dependenciesFile, false)
      }

      if (loader.exists(topLevelConfig))
        for {
          conf <- loader(topLevelConfig)
          deps <- resolveFromConfig(
            conf.withFallback(centralConfiguration.config),
            topLevelConfig,
            useMavenLibs,
            isMavenConfig,
            singleSourceDep,
            loadedResolvers,
            scalaMajorVersion)
            .withProblems(conf.checkExtraProperties(topLevelConfig, checkingKeys))
        } yield deps
      else
        topLevelConfig match {
          case DependenciesConfig      => missingMsg(isError = true) // dependencies.obt is required
          case MavenDependenciesConfig => missingMsg(isError = false) // maven-dependencies.obt is optional
          case _                       => missingMsg(isError = false) // rest src/dependencies/*.obt are optional
        }
    }

    DependenciesConfig.tryWith {
      for {
        deps <- getCentralDependencies(DependenciesConfig)
        mavenDeps <- getCentralDependencies(MavenDependenciesConfig)
        buildDeps <- getCentralDependencies(BuildDependenciesConfig)
        singleSourceDeps = deps ++ mavenDeps ++ buildDeps
        multipleSourceDeps <- getCentralDependencies(JvmDependenciesConfig, Some(singleSourceDeps))
      } yield deps ++ mavenDeps ++ buildDeps ++ multipleSourceDeps
    }
  }
}
