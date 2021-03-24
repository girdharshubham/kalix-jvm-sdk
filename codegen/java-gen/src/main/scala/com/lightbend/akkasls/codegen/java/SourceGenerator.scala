/*
 * Copyright (c) Lightbend Inc. 2021
 *
 */

package com.lightbend.akkasls.codegen
package java

import com.google.common.base.Charsets
import org.bitbucket.inkytonik.kiama.output.PrettyPrinter
import org.bitbucket.inkytonik.kiama.output.PrettyPrinterTypes.Document

import _root_.java.nio.file.{ Files, Path, Paths }

/**
  * Responsible for generating Java source from an entity model
  */
object SourceGenerator extends PrettyPrinter {

  /**
    * Generate Java source from entities where the target source and test source directories have no existing source.
    * Note that we only generate tests for entities where we are successful in generating an entity. The user may
    * not want a test otherwise.
    *
    * Also generates a main source file if it does not already exist.
    *
    * Impure.
    *
    * @param entities        The model of entity metadata to generate source file
    * @param sourceDirectory A directory to generate source files in, which can also containing existing source.
    * @param testSourceDirectory A directory to generate test source files in, which can also containing existing source.
    * @param mainClass  A fully qualified classname to be used as the main class
    * @return A collection of paths addressing source files generated by this function
    */
  def generate(
      entities: Iterable[ModelBuilder.Entity],
      sourceDirectory: Path,
      testSourceDirectory: Path,
      mainClass: String
  ): Iterable[Path] = {

    def packageAsPath(packageName: String): Path =
      Paths.get(packageName.replace(".", "/"))

    entities.flatMap { case entity: ModelBuilder.EventSourcedEntity =>
      val (packageName, className) = dissassembleClassName(entity.fullName)
      val packagePath              = packageAsPath(packageName)

      val sourcePath = sourceDirectory.resolve(packagePath.resolve(className + ".java"))
      if (!sourcePath.toFile.exists()) {
        // We're going to generate an entity - let's see if we can generate its test...
        val testClassName = className + "Test"
        val testSourcePath =
          testSourceDirectory.resolve(packagePath.resolve(testClassName + ".java"))
        val testSourceFiles = if (!testSourcePath.toFile.exists()) {
          val _ = testSourcePath.getParent.toFile.mkdirs()
          val _ = Files.write(
            testSourcePath,
            testSource(entity, packageName, className, testClassName).layout.getBytes(
              Charsets.UTF_8
            )
          )
          List(testSourcePath)
        } else {
          List.empty
        }

        // Now we generate the entity
        val _ = sourcePath.getParent.toFile.mkdirs()
        val _ = Files.write(
          sourcePath,
          source(entity, packageName, className).layout.getBytes(Charsets.UTF_8)
        )

        List(sourcePath) ++ testSourceFiles
      } else {
        List.empty
      }
    } ++ {
      if (entities.nonEmpty) {
        // Generate a main source file is it is not there already
        val (mainClassPackageName, mainClassName) = dissassembleClassName(mainClass)
        val mainClassPackagePath                  = packageAsPath(mainClassPackageName)

        val mainClassPath =
          sourceDirectory.resolve(mainClassPackagePath.resolve(mainClassName + ".java"))
        if (!mainClassPath.toFile.exists()) {
          val _ = mainClassPath.getParent.toFile.mkdirs()
          val _ = Files.write(
            mainClassPath,
            mainSource(mainClassPackageName, mainClassName, entities).layout.getBytes(
              Charsets.UTF_8
            )
          )
          List(mainClassPath)
        } else {
          List.empty
        }
      } else {
        List.empty
      }
    }
  }

  private[codegen] def source(
      entity: ModelBuilder.EventSourcedEntity,
      packageName: String,
      className: String
  ): Document =
    pretty(
      "package" <+> packageName <> semi <> line <>
      line <>
      "import" <+> "com.google.protobuf.Empty" <> semi <> line <>
      "import" <+> "com.akkaserverless.javasdk.EntityId" <> semi <> line <>
      "import" <+> "com.akkaserverless.javasdk.eventsourcedentity.*" <> semi <> line <>
      line <>
      "/** An event sourced entity. */" <> line <>
      "@EventSourcedEntity" <> parens("entityType" <+> equal <+> dquotes(className)) <> line <>
      `class`("public", className) {
        "@SuppressWarnings" <> parens(dquotes("unused")) <> line <>
        "private" <+> "final" <+> "String" <+> "entityId" <> semi <> line <>
        line <>
        constructor(
          "public",
          className,
          List("@EntityId" <+> "String" <+> "entityId")
        ) {
          "this.entityId" <+> equal <+> "entityId" <> semi
        } <> line <>
        line <>
        ssep(
          entity.commands.toSeq.map { command =>
            "@CommandHandler" <>
            line <>
            method(
              "public",
              qualifiedType(command.outputType, entity.javaOuterClassname),
              lowerFirst(name(command.fullname)),
              List(
                qualifiedType(command.inputType, entity.javaOuterClassname) <+> "command",
                "CommandContext" <+> "ctx"
              ),
              emptyDoc
            ) {
              "ctx.fail(\"The command handler for `" <> name(
                command.fullname
              ) <> "` is not implemented, yet\")" <> semi <> line <>
              "throw new RuntimeException()" <> semi <+> "// This line is never reached, as ctx.fail throws, but is required to avoid compiler errors"
            }
          },
          line <> line
        )
      }
    )

  private[codegen] def testSource(
      entity: ModelBuilder.EventSourcedEntity,
      packageName: String,
      className: String,
      testClassName: String
  ): Document =
    pretty(
      "package" <+> packageName <> semi <> line <>
      line <>
      "import" <+> "com.akkaserverless.javasdk.eventsourcedentity.CommandContext" <> semi <> line <>
      "import" <+> "org.junit.Test" <> semi <> line <>
      "import" <+> "org.mockito.*" <> semi <> line <>
      line <>
      `class`("public", testClassName) {
        "private" <+> "String" <+> "entityId" <+> equal <+> """"entityId1"""" <> semi <> line <>
        "private" <+> className <+> "entity" <> semi <> line <>
        "private" <+> "CommandContext" <+> "context" <+> equal <+> "Mockito.mock(CommandContext.class)" <> semi <> line <>
        line <>
        ssep(
          entity.commands.toSeq.map { command =>
            "@Test" <> line <>
            method(
              "public",
              "void",
              lowerFirst(name(command.fullname)) + "Test",
              List.empty,
              emptyDoc
            ) {
              "entity" <+> equal <+> "new" <+> className <> parens("entityId") <> semi <> line <>
              line <>
              "// TODO: you may want to set fields in addition to the entity id" <> line <>
              "//" <> indent(
                "entity" <> dot <> lowerFirst(name(command.fullname)) <> parens(
                  qualifiedType(
                    command.inputType,
                    entity.javaOuterClassname
                  ) <> dot <> "newBuilder().setEntityId(entityId).build(), context"
                )
              ) <> semi <> line <>
              line <>
              "// TODO: if you wish to verify events:" <> line <>
              "//" <> indent("Mockito.verify(context).emit(event)") <> semi
            }
          },
          line <> line
        )
      }
    )

  private[codegen] def mainSource(
      mainClassPackageName: String,
      mainClassName: String,
      entities: Iterable[ModelBuilder.Entity]
  ): Document = {
    assert(entities.nonEmpty) // Pointless to generate a main if empty, so don't try

    val entityClasses = entities.map { case entity: ModelBuilder.EventSourcedEntity =>
      val (packageName, className) = dissassembleClassName(entity.fullName)
      // Package names should be relative to the main class's one
      if (packageName == mainClassPackageName)
        (Option.empty[String], className, entity.javaOuterClassname)
      else
        (Some(packageName), className, entity.javaOuterClassname)
    }

    val imports = List(
      "import" <+> "com.akkaserverless.javasdk.AkkaServerless" <> semi
    ) ++
      entityClasses.toSeq.collect { case (Some(packageName), className, javaOuterClassName) =>
        javaOuterClassName.fold(emptyDoc)(ocn =>
          "import" <+> packageName <> dot <> ocn <> semi <> line
        ) <>
          "import" <+> packageName <> dot <> className <> semi
      }

    pretty(
      "package" <+> mainClassPackageName <> semi <> line <>
      line <>
      ssep(
        imports,
        line
      ) <> line <>
      line <>
      `class`("public" <+> "final", mainClassName) {
        line <>
        method(
          "public" <+> "static",
          "void",
          "main",
          List("String[]" <+> "args"),
          "throws" <+> "Exception" <> space
        ) {
          "new" <+> "AkkaServerless()" <+> "//" <> line <>
          indent(
            ssep(
              entityClasses.map {
                case (_, className, Some(javaOuterClassName)) =>
                  ".registerEventSourcedEntity" <> parens(
                    className <> ".class" <> comma <+> javaOuterClassName <> ".getDescriptor().findServiceByName" <> parens(
                      dquotes(className)
                    )
                  ) <+> "//"
                case (_, className, None) =>
                  "// FIXME: No Java outer class name specified - cannot register" <+> className <+> "- ensure you are generating protobuf for Java"
              }.toSeq,
              line
            ) <> line <>
            ".start().toCompletableFuture().get()" <> semi
          )
        } <> line

      }
    )
  }

  private def dissassembleClassName(fullClassName: String): (String, String) = {
    val className   = fullClassName.reverse.takeWhile(_ != '.').reverse
    val packageName = fullClassName.dropRight(className.length + 1)
    packageName -> className
  }

  private def `class`(modifier: Doc, name: String)(body: Doc): Doc =
    modifier <+> "class" <+> name <+>
    braces(nest(line <> body) <> line)

  private def constructor(
      modifier: Doc,
      name: String,
      parameters: Seq[Doc]
  )(body: Doc): Doc =
    modifier <+> name <> parens(ssep(parameters, comma <> space)) <+>
    braces(nest(line <> body) <> line)

  private def method(
      modifier: Doc,
      returnType: String,
      name: String,
      parameters: Seq[Doc],
      postModifier: Doc
  )(body: Doc): Doc =
    modifier <+> returnType <+> name <> parens(ssep(parameters, comma <> space)) <+> postModifier <>
    braces(nest(line <> body) <> line)

  private def name(`type`: String): String =
    `type`.reverse.takeWhile(_ != '.').reverse

  private def qualifiedType(`type`: String, outerClassname: Option[String]): String =
    if (`type` == "google.protobuf.Empty")
      name(`type`)
    else
      outerClassname.fold("")(_ + ".") + name(`type`)

  private def lowerFirst(text: String): String =
    text.headOption match {
      case Some(c) => c.toLower.toString + text.drop(1)
      case None    => ""
    }

}
