/*
 * Copyright 2012 Kazuhiro Sera
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package scalikejdbc.mapper

import scalikejdbc._
import scala.language.implicitConversions
import java.util.Locale.{ ENGLISH => en }

/**
 * Active Record like template generator
 */
class CodeGenerator(table: Table, specifiedClassName: Option[String] = None)(implicit config: GeneratorConfig = GeneratorConfig())
    extends LoanPattern {

  import java.sql.{ Types => JavaSqlTypes }
  import java.io.{ OutputStreamWriter, FileOutputStream, File }

  private val packageName = config.packageName
  private val className = specifiedClassName.getOrElse(toClassName(table))
  private val syntaxName = {
    val name = "[A-Z]".r.findAllIn(className).mkString.toLowerCase(en)
    if (name == "rs") "r" else name
  }
  private val comma = ","
  private val eol = config.lineBreak.value

  object TypeName {
    val Any = "Any"
    val AnyArray = "Array[Any]"
    val ByteArray = "Array[Byte]"
    val Long = "Long"
    val Boolean = "Boolean"
    val DateTime = "DateTime"
    val LocalDate = "LocalDate"
    val LocalTime = "LocalTime"
    val String = "String"
    val Byte = "Byte"
    val Int = "Int"
    val Short = "Short"
    val Float = "Float"
    val Double = "Double"
    val Blob = "Blob"
    val Clob = "Clob"
    val Ref = "Ref"
    val Struct = "Struct"
    val BigDecimal = "BigDecimal" // scala.math.BigDecimal
  }

  case class IndentGenerator(i: Int) {
    def indent: String = " " * i * 2
  }

  implicit def convertIntToIndentGenerator(i: Int) = IndentGenerator(i)

  case class ColumnInScala(underlying: Column) {

    lazy val nameInScala: String = {
      val camelCase: String = toCamelCase(underlying.name)
      camelCase.head.toLower + camelCase.tail
    }

    lazy val rawTypeInScala: String = underlying.dataType match {
      case JavaSqlTypes.ARRAY => TypeName.AnyArray
      case JavaSqlTypes.BIGINT => TypeName.Long
      case JavaSqlTypes.BINARY => TypeName.ByteArray
      case JavaSqlTypes.BIT => TypeName.Boolean
      case JavaSqlTypes.BLOB => TypeName.Blob
      case JavaSqlTypes.BOOLEAN => TypeName.Boolean
      case JavaSqlTypes.CHAR => TypeName.String
      case JavaSqlTypes.CLOB => TypeName.Clob
      case JavaSqlTypes.DATALINK => TypeName.Any
      case JavaSqlTypes.DATE => TypeName.LocalDate
      case JavaSqlTypes.DECIMAL => TypeName.BigDecimal
      case JavaSqlTypes.DISTINCT => TypeName.Any
      case JavaSqlTypes.DOUBLE => TypeName.Double
      case JavaSqlTypes.FLOAT => TypeName.Float
      case JavaSqlTypes.INTEGER => TypeName.Int
      case JavaSqlTypes.JAVA_OBJECT => TypeName.Any
      case JavaSqlTypes.LONGVARBINARY => TypeName.ByteArray
      case JavaSqlTypes.LONGVARCHAR => TypeName.String
      case JavaSqlTypes.NULL => TypeName.Any
      case JavaSqlTypes.NUMERIC => TypeName.BigDecimal
      case JavaSqlTypes.OTHER => TypeName.Any
      case JavaSqlTypes.REAL => TypeName.Float
      case JavaSqlTypes.REF => TypeName.Ref
      case JavaSqlTypes.SMALLINT => TypeName.Short
      case JavaSqlTypes.STRUCT => TypeName.Struct
      case JavaSqlTypes.TIME => TypeName.LocalTime
      case JavaSqlTypes.TIMESTAMP => TypeName.DateTime
      case JavaSqlTypes.TINYINT => TypeName.Byte
      case JavaSqlTypes.VARBINARY => TypeName.ByteArray
      case JavaSqlTypes.VARCHAR => TypeName.String
      case _ => TypeName.Any
    }

    lazy val typeInScala: String = {
      if (underlying.isNotNull) rawTypeInScala
      else "Option[" + rawTypeInScala + "]"
    }

    lazy val dummyValue: String = underlying.dataType match {
      case JavaSqlTypes.ARRAY => "null"
      case JavaSqlTypes.BIGINT => "1"
      case JavaSqlTypes.BINARY => "1"
      case JavaSqlTypes.BIT => "false"
      case JavaSqlTypes.BLOB => "null"
      case JavaSqlTypes.BOOLEAN => "true"
      case JavaSqlTypes.CHAR => "'abc'"
      case JavaSqlTypes.CLOB => "null"
      case JavaSqlTypes.DATALINK => "null"
      case JavaSqlTypes.DATE => "'1958-09-06'"
      case JavaSqlTypes.DECIMAL => "1"
      case JavaSqlTypes.DISTINCT => "null"
      case JavaSqlTypes.DOUBLE => "0.1"
      case JavaSqlTypes.FLOAT => "0.1"
      case JavaSqlTypes.INTEGER => "1"
      case JavaSqlTypes.JAVA_OBJECT => "null"
      case JavaSqlTypes.LONGVARBINARY => "null"
      case JavaSqlTypes.LONGVARCHAR => "'abc'"
      case JavaSqlTypes.NULL => "null"
      case JavaSqlTypes.NUMERIC => "1"
      case JavaSqlTypes.OTHER => "null"
      case JavaSqlTypes.REAL => "null"
      case JavaSqlTypes.REF => "null"
      case JavaSqlTypes.SMALLINT => "1"
      case JavaSqlTypes.STRUCT => "null"
      case JavaSqlTypes.TIME => "'12:00:00'"
      case JavaSqlTypes.TIMESTAMP => "'1958-09-06 12:00:00'"
      case JavaSqlTypes.TINYINT => "1"
      case JavaSqlTypes.VARBINARY => "null"
      case JavaSqlTypes.VARCHAR => "'abc'"
      case _ => "null"
    }

    lazy val defaultValueInScala: String = underlying.typeInScala match {
      case TypeName.AnyArray => "Array[Any]()"
      case TypeName.Long => "1L"
      case TypeName.ByteArray => "Array[Byte]()"
      case TypeName.Boolean => "false"
      case TypeName.String => "\"MyString\""
      case TypeName.LocalDate => "LocalDate.now"
      case TypeName.BigDecimal => "new java.math.BigDecimal(\"1\")"
      case TypeName.Double => "0.1D"
      case TypeName.Float => "0.1F"
      case TypeName.Int => "123"
      case TypeName.Short => "123"
      case TypeName.DateTime => "DateTime.now"
      case TypeName.Byte => "1"
      case _ => "null"
    }

  }

  /**
   * Create directory to put the source code file if it does not exist yet.
   */
  def mkdirRecursively(file: File): Unit = {
    if (!file.getParentFile.exists) mkdirRecursively(file.getParentFile)
    if (!file.exists) file.mkdir()
  }

  implicit def convertColumnToColumnInScala(column: Column): ColumnInScala = ColumnInScala(column)

  /**
   * Write the source code to file.
   */
  def writeModelIfNotExist(): Unit = {
    val file = new File(config.srcDir + "/" + packageName.replace(".", "/") + "/" + className + ".scala")
    if (file.exists) {
      println("\"" + packageName + "." + className + "\"" + " already exists.")
    } else {
      mkdirRecursively(file.getParentFile)
      using(new FileOutputStream(file)) {
        fos =>
          using(new OutputStreamWriter(fos)) {
            writer =>
              writer.write(modelAll())
              println("\"" + packageName + "." + className + "\"" + " created.")
          }
      }
    }
  }

  /**
   * Class part.
   *
   * {{{
   * case class Member(id: Long, name: String, description: Option[String])) {
   *   def save(): Member = Member.update(this)
   *   def destroy(): Unit = Member.delete(this)
   * }
   * }}}
   */
  def classPart: String = {
    if (config.caseClassOnly || table.allColumns.size <= 22) {
      val constructorArgs = table.allColumns.map {
        c => 1.indent + c.nameInScala + ": " + c.typeInScala + (if (c.isNotNull) "" else " = None")
      }.mkString(", " + eol)

      s"""case class ${className}(
        |${constructorArgs}) {
        |
        |  def save()(implicit session: DBSession = ${className}.autoSession): ${className} = ${className}.save(this)(session)
        |
        |  def destroy()(implicit session: DBSession = ${className}.autoSession): Unit = ${className}.destroy(this)(session)
        |
        |}
      """.stripMargin

    } else {

      val constructorArgs1 = table.allColumns.map {
        c => 1.indent + "val " + c.nameInScala + ": " + c.typeInScala + (if (c.isNotNull) "" else " = None")
      }.mkString(comma + eol)
      val copyArgs = table.allColumns.map {
        c => 2.indent + c.nameInScala + ": " + c.typeInScala + " = this." + c.nameInScala
      }.mkString(comma + eol)
      val constructorArgs3 = table.allColumns.map {
        c => 3.indent + c.nameInScala + " = " + c.nameInScala
      }.mkString(comma + eol)

      s"""class ${className}(
        |${constructorArgs1}) {
        |
        |  def copy(
        |${copyArgs}): ${className} = {
        |    new ${className}(
        |${constructorArgs3})
        |  }
        |
        |  def save()(implicit session: DBSession = ${className}.autoSession): ${className} = ${className}.save(this)(session)
        |
        |  def destroy()(implicit session: DBSession = ${className}.autoSession): Unit = ${className}.destroy(this)(session)
        |
        |}
      """.stripMargin
    }
  }

  /**
   * {{{
   * object Member {
   *   // ... as follows
   * }
   * }}}
   */
  def objectPart: String = {

    val allColumns = table.allColumns
    val pkColumns = if (table.primaryKeyColumns.size == 0) allColumns else table.primaryKeyColumns

    /**
     * {{{
     * val tableName = "member"
     * }}}
     */
    val tableName = "  val tableName = \"" + table.name + "\"" + eol

    /**
     * {{{
     * object columnNames {
     *   val id = "ID"
     *   val name = "NAME"
     *   val birthday = "BIRTHDAY"
     *   val all = Seq(id, name, birthday)
     * }
     * }}}
     */
    val columnNames = {
      val valueDefinitions = allColumns.map { c => 2.indent + "val " + c.nameInScala + " = \"" + c.name + "\"" }.mkString(eol)
      val valueNames = allColumns.map { c => c.nameInScala }.mkString(", ")

      s"""  object columnNames {
        |${valueDefinitions}
        |    val all = Seq(${valueNames})
        |    val inSQL = all.mkString(", ")
        |  }
      """.stripMargin
    }

    val _mapper = {
      2.indent + "(rs: WrappedResultSet) => " + (if (allColumns.size > 22) "new " else "") + className + "(" + eol +
        allColumns.map { c => 3.indent + c.nameInScala + " = rs.get(" + c.nameInScala + ")" }
        .mkString(comma + eol) + ")"
    }

    /**
     * {{{
     * val * = {
     *   import columnNames._
     *   (rs: WrappedResultSet) => Member(
     *     rs.long(id),
     *     rs.string(name),
     *     Option(rs.date(birthday)).map(_.toLocalDate)
     *   )
     * }
     * }}}
     */
    val mapper = {
      s"""  val * = {
        |    import columnNames._
        |${_mapper}
        |  }
      """.stripMargin
    }

    val interpolationMapper = {
      val _interpolationMapper = allColumns.map { c => 2.indent + c.nameInScala + " = rs.get(" + syntaxName + "." + c.nameInScala + ")" }
        .mkString(comma + eol)
      s"""  def apply(${syntaxName}: SyntaxProvider[${className}])(rs: WrappedResultSet): ${className} = apply(${syntaxName}.resultName)(rs)
        |  def apply(${syntaxName}: ResultName[${className}])(rs: WrappedResultSet): ${className} = new ${className}(
        |${_interpolationMapper}
        |  )
      """.stripMargin
    }

    /**
     * {{{
     * object joinedColumnNames {
     *   val delimiter = "__ON__"
     *   def as(name: String) = name + delimiter + tableName
     *   val id = as(columnNames.id)
     *   val name = as(columnNames.name)
     *   val birthday = as(columnNames.birthday)
     *   val all = Seq(id, name, birthday)
     *   val inSQL = columnNames.all.map(name => tableName + "." + name + " AS " + as(name)).mkString(", ")
     * }
     * }}}
     */
    val joinedColumnNames = {
      val valueDefinitions = allColumns.map { c => 2.indent + "val " + c.nameInScala + " = as(columnNames." + c.nameInScala + ")" }.mkString(eol)
      val valueNames = allColumns.map { c => c.nameInScala }.mkString(", ")
      s"""  object joinedColumnNames {
        |    val delimiter = "__ON__"
        |    def as(name: String) = name + delimiter + tableName
        |${valueDefinitions}
        |    val all = Seq(${valueNames})
        |    val inSQL = columnNames.all.map(name => tableName + "." + name + " AS " + as(name)).mkString(", ")
        |  }
      """.stripMargin
    }

    /**
     * {{{
     * val joined = {
     *   import joinedColumnNames._
     *   (rs: WrappedResultSet) => Member(
     *     rs.long(id),
     *     rs.string(name),
     *     Option(rs.date(birthday)).map(_.toLocalDate)
     *   )
     * }
     * }}}
     */
    val joinedMapper = {
      s"""  val joined = {
        |    import joinedColumnNames._
        |${_mapper}
        |  }
      """.stripMargin
    }

    /**
     * {{{
     * val autoSession = AutoSession
     * }}}
     */
    val autoSession = "  override val autoSession = AutoSession" + eol

    /**
     * {{{
     * def create(name: String, birthday: Option[LocalDate])(implicit session: DBSession = autoSession): Member = {
     *   val generatedKey = SQL("""
     *     insert into member (
     *       NAME,
     *       BIRTHDAY
     *     ) VALUES (
     *       /*'name*/'abc',
     *       /*'birthday*/'1958-09-06'
     *     )
     *   """).bindByName(
     *     'name -> name,
     *     'birthday -> birthday
     *   ).updateAndReturnGeneratedKey.apply()
     *
     *   Member(
     *     id = generatedKey,
     *     name = name,
     *     birthday = birthday
     *   )
     * }
     * }}}
     */
    val createMethod = {
      val createColumns: List[Column] = allColumns.filterNot {
        c => table.autoIncrementColumns.exists(_.name == c.name)
      }
      val placeHolderPart: String = config.template match {
        case GeneratorTemplate.queryDsl =>
          // id, name
          createColumns.map(c => 4.indent + c.nameInScala).mkString(comma + eol)
        case GeneratorTemplate.interpolation | GeneratorTemplate.queryDsl =>
          // ${id}, ${name}
          createColumns.map(c => 4.indent + "${" + c.nameInScala + "}").mkString(comma + eol)
        case _ =>
          // {id}, {name}
          createColumns.map(c => 4.indent + "{" + c.nameInScala + "}").mkString(comma + eol)
      }

      val bindingPart: String = config.template match {
        case GeneratorTemplate.interpolation | GeneratorTemplate.queryDsl => ""
        case _ =>
          // .bindByName('id -> id, 'name -> name
          3.indent + ".bindByName(" + eol +
            createColumns.map { c => 4.indent + "'" + c.nameInScala + " -> " + c.nameInScala }.mkString(comma + eol)
      }

      // def create(
      1.indent + "def create(" + eol +
        // id: Long, name: Option[String] = None)(implicit session DBSession = autoSession): ClassName = {
        createColumns.map { c => 2.indent + c.nameInScala + ": " + c.typeInScala + (if (c.isNotNull) "" else " = None") }.mkString(comma + eol) +
        ")(implicit session: DBSession = autoSession): " + className + " = {" + eol +
        // val generatedKey =       
        2.indent + table.autoIncrementColumns.headOption.map(_ => "val generatedKey = ").getOrElse("") +
        (config.template match {
          case GeneratorTemplate.queryDsl =>
            // withSQL { insert.into(User).columns(
            "withSQL {" + eol + 3.indent + "insert.into(" + className + ").columns("
          case GeneratorTemplate.interpolation => "sql\"\"\"" + eol + 3.indent + "insert into ${" + className + ".table} ("
          case _ => "SQL(\"\"\"" + eol + 3.indent + "insert into " + table.name + " ("
        }) + eol +
        (config.template match {
          case GeneratorTemplate.queryDsl =>
            createColumns.map(c => 4.indent + "column." + c.nameInScala).mkString(comma + eol) + eol + 3.indent + ").values(" + eol
          case GeneratorTemplate.interpolation =>
            createColumns.map(c => 4.indent + "${" + "column." + c.nameInScala + "}").mkString(comma + eol) + eol + 3.indent + ") values (" + eol
          case _ =>
            createColumns.map(c => 4.indent + c.name).mkString(comma + eol) + eol + 3.indent + ") values (" + eol
        }) +
        placeHolderPart + eol + 3.indent + ")" + eol +
        (config.template match {
          case GeneratorTemplate.queryDsl =>
            2.indent + table.autoIncrementColumns.headOption.map(_ => "}.updateAndReturnGeneratedKey.apply()").getOrElse("}.update.apply()")
          case GeneratorTemplate.interpolation =>
            3.indent + "\"\"\"" + table.autoIncrementColumns.headOption.map(_ => ".updateAndReturnGeneratedKey.apply()").getOrElse(".update.apply()")
          case _ =>
            3.indent + "\"\"\")" + eol +
              bindingPart + eol +
              3.indent + table.autoIncrementColumns.headOption.map(_ => ").updateAndReturnGeneratedKey.apply()").getOrElse(").update.apply()")
        }) +
        eol +
        eol +
        2.indent + (if (allColumns.size > 22) "new " else "") + className + "(" + eol +
        table.autoIncrementColumns.headOption.map { c =>
          3.indent + c.nameInScala +
            (c.typeInScala match {
              case TypeName.Byte => " = generatedKey.toByte, "
              case TypeName.Int => " = generatedKey.toInt, "
              case TypeName.Short => " = generatedKey.toShort, "
              case TypeName.Float => " = generatedKey.toFloat, "
              case TypeName.Double => " = generatedKey.toDouble, "
              case TypeName.String => " = generatedKey.toString, "
              case TypeName.BigDecimal => " = BigDecimal.valueOf(generatedKey), "
              case _ => " = generatedKey, "
            }) + eol
        }.getOrElse("") +
        createColumns.map { c => 3.indent + c.nameInScala + " = " + c.nameInScala }.mkString(comma + eol) + ")" + eol +
        1.indent + "}" + eol
    }

    /**
     * {{{
     * def save(entity: Member)(implicit session: DBSession = autoSession): Member = {
     *   SQL("""
     *     update
     *       member
     *     set
     *       ID = /*'id*/123,
     *       NAME = /*'name*/'abc',
     *       BIRTHDAY = /*'birthday*/'1958-09-06'
     *     where
     *       ID = /*'id*/123
     * """).bindByName(
     *     'id -> entity.id,
     *     'name -> entity.name,
     *     'birthday -> entity.birthday
     *   ).update.apply()
     *   entity
     * }
     * }}}
     */
    val saveMethod = {

      val placeHolderPart: String = config.template match {
        case GeneratorTemplate.queryDsl =>
          // column.id -> entity.id, column.name -> entity.name
          allColumns.map(c => 4.indent + "column." + c.nameInScala + " -> entity." + c.nameInScala).mkString(comma + eol)
        case GeneratorTemplate.interpolation =>
          // ${column.id} = ${entity.id}, ${column.name} = ${entity.name}
          allColumns.map(c => 4.indent + "${column." + c.nameInScala + "} = ${entity." + c.nameInScala + "}").mkString(comma + eol)
        case _ =>
          // id = {id}, name = {name}
          allColumns.map(c => 4.indent + c.name + " = {" + c.nameInScala + "}").mkString(comma + eol)
      }

      val wherePart = config.template match {
        case GeneratorTemplate.queryDsl =>
          // .eq(column.id, entity.id).and.eq(column.name, entity.name)
          pkColumns.map(pk => ".eq(column." + pk.nameInScala + ", entity." + pk.nameInScala + ")").mkString(".and")
        case GeneratorTemplate.interpolation =>
          // ${column.id} = ${entity.id} and ${column.name} = ${entity.name}
          4.indent + pkColumns.map(pk => "${" + "column." + pk.nameInScala + "} = ${entity." + pk.nameInScala + "}").mkString(" and ")
        case _ =>
          // id = {id} and name = {name}
          4.indent + pkColumns.map(pk => pk.name + " = {" + pk.nameInScala + "}").mkString(" and ")
      }

      val bindingPart = config.template match {
        case GeneratorTemplate.interpolation | GeneratorTemplate.queryDsl => ""
        case _ =>
          3.indent + ".bindByName(" + eol +
            allColumns.map(c => 4.indent + "'" + c.nameInScala + " -> entity." + c.nameInScala).mkString(comma + eol) + eol +
            3.indent + ")"
      }

      (config.template match {
        case GeneratorTemplate.queryDsl =>
          s"""  def save(entity: ${className})(implicit session: DBSession = autoSession): ${className} = {
          |    withSQL { 
          |      update(${className}).set(
          |${placeHolderPart}
          |      ).where${wherePart}
          |    }.update.apply()
          |    entity 
          |  }
        """
        case GeneratorTemplate.interpolation =>
          s"""  def save(entity: ${className})(implicit session: DBSession = autoSession): ${className} = {
          |    sql\"\"\"
          |      update
          |        $${${className}.table}
          |      set
          |${placeHolderPart}
          |      where
          |${wherePart}
          |      \"\"\".update.apply()
          |    entity 
          |  }
        """
        case _ =>
          s"""  def save(entity: ${className})(implicit session: DBSession = autoSession): ${className} = {
          |    SQL(\"\"\"
          |      update
          |        ${tableName}
          |      set
          |${placeHolderPart}
          |      where
          |${wherePart}
          |      \"\"\")
          |${bindingPart}.update.apply()
          |    entity
          |  }
        """
      }).stripMargin
    }

    /**
     * {{{
     * def destroy(entity: Member)(implicit session: DBSession = autoSession): Unit = {
     *   SQL("""delete from member where id = /*'id*/123""")
     *     .bindByName('id -> entity.id)
     *     .update.apply()
     * }
     * }}}
     */
    val destroyMethod = {

      val wherePart: String = config.template match {
        case GeneratorTemplate.queryDsl =>
          // .eq(column.id, entity.id).and.eq(column.name, entity.name)
          pkColumns.map(pk => ".eq(column." + pk.nameInScala + ", entity." + pk.nameInScala + ")").mkString(".and")
        case GeneratorTemplate.interpolation =>
          // ${column.id} = ${entity.id} and ${column.name} = ${entity.name}
          pkColumns.map(pk => "\\${" + "column." + pk.nameInScala + "} = \\${entity." + pk.nameInScala + "}").mkString(" and ")
        case _ =>
          pkColumns.map(pk => pk.name + " = {" + pk.nameInScala + "}").mkString(" and ")
      }

      val bindingPart: String = config.template match {
        case GeneratorTemplate.interpolation | GeneratorTemplate.queryDsl => ""
        case _ => ".bindByName(" + pkColumns.map(pk => "'" + pk.nameInScala + " -> entity." + pk.nameInScala).mkString(", ") + ")"
      }

      (config.template match {
        case GeneratorTemplate.queryDsl =>
          s"""  def destroy(entity: ${className})(implicit session: DBSession = autoSession): Unit = {
          |    withSQL { delete.from(${className}).where${wherePart} }.update.apply()
          |  }
        """
        case GeneratorTemplate.interpolation =>
          s"""  def destroy(entity: ${className})(implicit session: DBSession = autoSession): Unit = {
          |    sql\"\"\"delete from $${${className}.table} where ${wherePart}\"\"\".update.apply()
          |  }
        """
        case _ =>
          s"""  def destroy(entity: ${className})(implicit session: DBSession = autoSession): Unit = {
          |    SQL(\"\"\"delete from ${tableName} where ${wherePart}\"\"\")
          |      ${bindingPart}.update.apply()
          |  }
        """
      }).stripMargin
    }

    /**
     * {{{
     * def find(id: Long): Option[Member] = {
     *   DB readOnly { implicit session =>
     *     SQL("""select * from member where id = /*'id*/123""")
     *       .bindByName('id -> id).map(*).single.apply()
     *   }
     * }
     * }}}
     */
    val findMethod = {
      val argsPart = pkColumns.map(pk => pk.nameInScala + ": " + pk.typeInScala).mkString(", ")
      val wherePart = config.template match {
        case GeneratorTemplate.queryDsl =>
          pkColumns.map(pk => s".eq(${syntaxName}.${pk.nameInScala}, ${pk.nameInScala})").mkString(".and")
        case GeneratorTemplate.interpolation =>
          pkColumns.map(pk => s"${pk.name} = \\$${${syntaxName}.${pk.nameInScala}}").mkString(" and ")
        case _ =>
          pkColumns.map(pk => s"${pk.name} = {${pk.nameInScala}}").mkString(" and ")
      }
      val bindingPart = (config.template match {
        case GeneratorTemplate.interpolation | GeneratorTemplate.queryDsl => ""
        case _ => ".bindByName(" + pkColumns.map(pk => s"'${pk.nameInScala} -> ${pk.nameInScala}").mkString(", ")
      }) + ")"

      (config.template match {
        case GeneratorTemplate.queryDsl =>
          s"""  def find(${argsPart})(implicit session: DBSession = autoSession): Option[${className}] = {
            |    withSQL { 
            |      select.from(${className} as ${syntaxName}).where${wherePart}
            |    }.map(${className}(${syntaxName}.resultName)).single.apply()
            |  }
          """
        case GeneratorTemplate.interpolation =>
          s"""  def find(${argsPart})(implicit session: DBSession = autoSession): Option[${className}] = {
            |    sql\"\"\"select $${${syntaxName}.result.*} from $${${className} as ${syntaxName}} where ${wherePart}\"\"\"
            |      .map(${className}(${syntaxName}.resultName)).single.apply()
            |  }
          """
        case _ =>
          s"""  def find(${argsPart})(implicit session: DBSession = autoSession): Option[${className}] = {
            |    SQL(\"\"\"select * from ${tableName} where ${wherePart}\"\"\")
            |      ${bindingPart}.map(*).single.apply()
            |  }
          """
      }).stripMargin
    }

    /**
     * {{{
     * def countAll(): Long = {
     *   DB readOnly { implicit session =>
     *     SQL("""select count(1) from member""")
     *       .map(rs => rs.long(1)).single.apply().get
     *   }
     * }
     * }}}
     */
    val countAllMethod =
      (config.template match {
        case GeneratorTemplate.queryDsl =>
          s"""  def countAll()(implicit session: DBSession = autoSession): Long = {
            |    withSQL(select(sqls"count(1)").from(${className} as ${syntaxName})).map(rs => rs.long(1)).single.apply().get
            |  }
          """
        case GeneratorTemplate.interpolation =>
          s"""  def countAll()(implicit session: DBSession = autoSession): Long = {
            |    sql\"\"\"select count(1) from $${${className}.table}\"\"\".map(rs => rs.long(1)).single.apply().get
            |  }
          """
        case _ =>
          s"""  def countAll()(implicit session: DBSession = autoSession): Long = {
            |    SQL(\"\"\"select count(1) from ${table.name}\"\"\").map(rs => rs.long(1)).single.apply().get
            |  }
          """
      }).stripMargin

    /**
     * {{{
     * def findAll(): List[Member] = {
     *   DB readOnly { implicit session =>
     *     SQL("""select * from member""").map(*).list.apply()
     *   }
     * }
     * }}}
     */
    val findAllMethod =
      (config.template match {
        case GeneratorTemplate.queryDsl =>
          s"""  def findAll()(implicit session: DBSession = autoSession): List[${className}] = {
            |    withSQL(select.from(${className} as ${syntaxName})).map(${className}(${syntaxName}.resultName)).list.apply()
            |  }
          """
        case GeneratorTemplate.interpolation =>
          s"""  def findAll()(implicit session: DBSession = autoSession): List[${className}] = {
            |    sql\"\"\"select $${${syntaxName}.result.*} from $${${className} as ${syntaxName}}\"\"\".map(${className}(${syntaxName}.resultName)).list.apply()
            |  }
          """
        case _ =>
          s"""  def findAll()(implicit session: DBSession = autoSession): List[${className}] = {
            |    SQL(\"\"\"select * from ${table.name}\"\"\").map(*).list.apply()
            |  }
          """
      }).stripMargin

    /**
     * {{{
     * def findAllBy(where: String, params:(Symbol, Any)*): List[Member] = {
     *   DB readOnly { implicit session =>
     *     SQL("""select * from member """ + where)
     *       .bindByName(params: _*).map(*).list.apply()
     *   }
     * }
     * }}}
     */
    val findAllByMethod = {
      s"""  def findAllBy(where: String, params: (Symbol, Any)*)(implicit session: DBSession = autoSession): List[${className}] = {
        |    SQL(\"\"\"select * from ${table.name} where \"\"\" + where)
        |      .bindByName(params: _*).map(*).list.apply()
        |  }
      """.stripMargin
    }

    val interpolationFindAllByMethod = {
      s"""  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[${className}] = {
        |    sql\"\"\"select $${${syntaxName}.result.*} from $${${className} as ${syntaxName}} where $${where}\"\"\"
        |      .map(${className}(${syntaxName}.resultName)).list.apply()
        |  }
      """.stripMargin
    }

    val queryDslFindAllByMethod = {
      s"""  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[${className}] = {
        |    withSQL { 
        |      select.from(${className} as ${syntaxName}).where.append(sqls"$${where}")
        |    }.map(${className}(${syntaxName}.resultName)).list.apply()
        |  }
      """.stripMargin
    }

    /**
     * {{{
     * def countBy(where: String, params:(Symbol, Any)*): Long = {
     *   DB readOnly { implicit session =>
     *     SQL("""select count(1) from member """ + where)
     *       .bindByName(params: _*).map(*).single.apply().get
     *   }
     * }
     * }}}
     */
    val countByMethod = {
      s"""  def countBy(where: String, params: (Symbol, Any)*)(implicit session: DBSession = autoSession): Long = {
        |    SQL(\"\"\"select count(1) from ${table.name} where \"\"\" + where)
        |      .bindByName(params: _*).map(rs => rs.long(1)).single.apply().get
        |  }
      """.stripMargin
    }

    val interpolationCountByMethod = {
      s"""  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
        |    sql\"\"\"select count(1) from $${${className} as ${syntaxName}} where $${where}\"\"\"
        |      .map(_.long(1)).single.apply().get
        |  }
      """.stripMargin
    }

    val queryDslCountByMethod = {
      s"""  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
        |    withSQL { 
        |      select(sqls"count(1)").from(${className} as ${syntaxName}).where.append(sqls"$${where}")
        |    }.map(_.long(1)).single.apply().get
        |  }
      """.stripMargin
    }

    val isInterpolation = config.template == GeneratorTemplate.interpolation || config.template == GeneratorTemplate.queryDsl
    val isQueryDsl = config.template == GeneratorTemplate.queryDsl
    "object " + className + (if (isInterpolation) " extends SQLSyntaxSupport[" + className + "]" else "") + " {" + eol +
      eol +
      (if (isInterpolation) 1.indent + "override val tableName = \"" + table.name + "\"" + eol else tableName) +
      eol +
      (if (isInterpolation) 1.indent + "override val columns = Seq(" + allColumns.map(c => c.name).mkString("\"", "\", \"", "\"") + ")" + eol else columnNames) +
      eol +
      (if (isInterpolation) interpolationMapper else mapper) +
      eol +
      (if (isInterpolation) "" else joinedColumnNames + eol) +
      (if (isInterpolation) "" else joinedMapper + eol) +
      (if (isInterpolation) 1.indent + "val " + syntaxName + " = " + className + ".syntax(\"" + syntaxName + "\")" + eol + eol else "") +
      autoSession +
      eol +
      findMethod +
      eol +
      findAllMethod +
      eol +
      countAllMethod +
      eol +
      (if (isQueryDsl) queryDslFindAllByMethod else if (isInterpolation) interpolationFindAllByMethod else findAllByMethod) +
      eol +
      (if (isQueryDsl) queryDslCountByMethod else if (isInterpolation) interpolationCountByMethod else countByMethod) +
      eol +
      createMethod +
      eol +
      saveMethod +
      eol +
      destroyMethod +
      eol +
      "}"
  }

  def modelAll(): String = {
    val jodaTimeImport = table.allColumns.flatMap {
      c =>
        c.rawTypeInScala match {
          case TypeName.DateTime => Some("DateTime")
          case TypeName.LocalDate => Some("LocalDate")
          case TypeName.LocalTime => Some("LocalTime")
          case _ => None
        }
    } match {
      case classes if classes.size > 0 => "import org.joda.time.{" + classes.distinct.mkString(", ") + "}" + eol
      case _ => ""
    }
    val javaSqlImport = table.allColumns.flatMap {
      c =>
        c.rawTypeInScala match {
          case TypeName.Blob => Some("Blob")
          case TypeName.Clob => Some("Clob")
          case TypeName.Ref => Some("Ref")
          case TypeName.Struct => Some("Struct")
          case _ => None
        }
    } match {
      case classes if classes.size > 0 => "import java.sql.{" + classes.distinct.mkString(", ") + "}" + eol
      case _ => ""
    }
    "package " + config.packageName + eol +
      eol +
      "import scalikejdbc._" + eol +
      jodaTimeImport +
      javaSqlImport +
      eol +
      classPart + eol +
      eol +
      objectPart + eol
  }

  private def toClassName(table: Table): String = toCamelCase(table.name)

  private def toCamelCase(s: String): String = s.split("_").foldLeft("") {
    (camelCaseString, part) =>
      camelCaseString + toProperCase(part)
  }

  private def toProperCase(s: String): String = {
    if (s == null || s.trim.size == 0) ""
    else s.substring(0, 1).toUpperCase(en) + s.substring(1).toLowerCase(en)
  }

  // -----------------------
  // Spec
  // -----------------------

  def writeSpecIfNotExist(code: Option[String]): Unit = {
    val file = new File(config.testDir + "/" + packageName.replace(".", "/") + "/" + className + "Spec.scala")
    if (file.exists) {
      println("\"" + packageName + "." + className + "Spec\"" + " already exists.")
    } else {
      code.map { code =>
        mkdirRecursively(file.getParentFile)
        using(new FileOutputStream(file)) {
          fos =>
            using(new OutputStreamWriter(fos)) {
              writer =>
                writer.write(code)
                println("\"" + packageName + "." + className + "Spec\"" + " created.")
            }
        }
      }
    }
  }

  def specAll(): Option[String] = config.testTemplate match {
    case GeneratorTestTemplate.ScalaTestFlatSpec =>
      Some(replaceVariablesForTestPart(
        """package %package%
          |
          |import org.scalatest._
          |import org.joda.time._
          |import scalikejdbc.scalatest.AutoRollback
          |%interpolationImport%
          |
          |class %className%Spec extends fixture.FlatSpec with Matchers with AutoRollback {
          |  %syntaxObject%
          |
          |  behavior of "%className%"
          |
          |  it should "find by primary keys" in { implicit session =>
          |    val maybeFound = %className%.find(%primaryKeys%)
          |    maybeFound.isDefined should be(true)
          |  }
          |  it should "find all records" in { implicit session =>
          |    val allResults = %className%.findAll()
          |    allResults.size should be >(0)
          |  }
          |  it should "count all records" in { implicit session =>
          |    val count = %className%.countAll()
          |    count should be >(0L)
          |  }
          |  it should "find by where clauses" in { implicit session =>
          |    val results = %className%.findAllBy(%whereExample%)
          |    results.size should be >(0)
          |  }
          |  it should "count by where clauses" in { implicit session =>
          |    val count = %className%.countBy(%whereExample%)
          |    count should be >(0L)
          |  }
          |  it should "create new record" in { implicit session =>
          |    val created = %className%.create(%createFields%)
          |    created should not be(null)
          |  }
          |  it should "save a record" in { implicit session =>
          |    val entity = %className%.findAll().head
          |    val updated = %className%.save(entity)
          |    updated should not equal(entity)
          |  }
          |  it should "destroy a record" in { implicit session =>
          |    val entity = %className%.findAll().head
          |    %className%.destroy(entity)
          |    val shouldBeNone = %className%.find(%primaryKeys%)
          |    shouldBeNone.isDefined should be(false)
          |  }
          |
          |}
        """.stripMargin))
    case GeneratorTestTemplate.specs2unit =>
      Some(replaceVariablesForTestPart(
        """package %package%
          |
          |import scalikejdbc.specs2.mutable.AutoRollback
          |import org.specs2.mutable._
          |import org.joda.time._
          |%interpolationImport%
          |
          |class %className%Spec extends Specification {
          |  %syntaxObject%
          |
          |  "%className%" should {
          |    "find by primary keys" in new AutoRollback {
          |      val maybeFound = %className%.find(%primaryKeys%)
          |      maybeFound.isDefined should beTrue
          |    }
          |    "find all records" in new AutoRollback {
          |      val allResults = %className%.findAll()
          |      allResults.size should be_>(0)
          |    }
          |    "count all records" in new AutoRollback {
          |      val count = %className%.countAll()
          |      count should be_>(0L)
          |    }
          |    "find by where clauses" in new AutoRollback {
          |      val results = %className%.findAllBy(%whereExample%)
          |      results.size should be_>(0)
          |    }
          |    "count by where clauses" in new AutoRollback {
          |      val count = %className%.countBy(%whereExample%)
          |      count should be_>(0L)
          |    }
          |    "create new record" in new AutoRollback {
          |      val created = %className%.create(%createFields%)
          |      created should not beNull
          |    }
          |    "save a record" in new AutoRollback {
          |      val entity = %className%.findAll().head
          |      val updated = %className%.save(entity)
          |      updated should not equalTo(entity)
          |    }
          |    "destroy a record" in new AutoRollback {
          |      val entity = %className%.findAll().head
          |      %className%.destroy(entity)
          |      val shouldBeNone = %className%.find(%primaryKeys%)
          |      shouldBeNone.isDefined should beFalse
          |    }
          |  }
          |
          |}
        """.stripMargin))
    case GeneratorTestTemplate.specs2acceptance =>
      Some(replaceVariablesForTestPart(
        """package %package%
          |
          |import scalikejdbc.specs2.AutoRollback
          |import org.specs2._
          |import org.joda.time._
          |%interpolationImport%
          |
          |class %className%Spec extends Specification { def is =
          |
          |  "The '%className%' model should" ^
          |    "find by primary keys"         ! autoRollback().findByPrimaryKeys ^
          |    "find all records"             ! autoRollback().findAll ^
          |    "count all records"            ! autoRollback().countAll ^
          |    "find by where clauses"        ! autoRollback().findAllBy ^
          |    "count by where clauses"       ! autoRollback().countBy ^
          |    "create new record"            ! autoRollback().create ^
          |    "save a record"                ! autoRollback().save ^
          |    "destroy a record"             ! autoRollback().destroy ^
          |                                   end
          |
          |  case class autoRollback() extends AutoRollback {
          |    %syntaxObject%
          |
          |    def findByPrimaryKeys = this {
          |      val maybeFound = %className%.find(%primaryKeys%)
          |      maybeFound.isDefined should beTrue
          |    }
          |    def findAll = this {
          |      val allResults = %className%.findAll()
          |      allResults.size should be_>(0)
          |    }
          |    def countAll = this {
          |      val count = %className%.countAll()
          |      count should be_>(0L)
          |    }
          |    def findAllBy = this {
          |      val results = %className%.findAllBy(%whereExample%)
          |      results.size should be_>(0)
          |    }
          |    def countBy = this {
          |      val count = %className%.countBy(%whereExample%)
          |      count should be_>(0L)
          |    }
          |    def create = this {
          |      val created = %className%.create(%createFields%)
          |      created should not beNull
          |    }
          |    def save = this {
          |      val entity = %className%.findAll().head
          |      val updated = %className%.save(entity)
          |      updated should not equalTo(entity)
          |    }
          |    def destroy = this {
          |      val entity = %className%.findAll().head
          |      %className%.destroy(entity)
          |      val shouldBeNone = %className%.find(%primaryKeys%)
          |      shouldBeNone.isDefined should beFalse
          |    }
          |  }
          |
          |}
        """.stripMargin))
    case GeneratorTestTemplate(name) => None
  }

  private def replaceVariablesForTestPart(code: String): String = {
    val isInterpolation = config.template == GeneratorTemplate.interpolation || config.template == GeneratorTemplate.queryDsl
    val isQueryDsl = config.template == GeneratorTemplate.queryDsl
    code.replace("%package%", packageName)
      .replace("%className%", className)
      .replace("%interpolationImport%", if (isInterpolation) "import scalikejdbc.SQLInterpolation._" else "")
      .replace("%primaryKeys%", table.primaryKeyColumns.map {
        c => c.defaultValueInScala
      }.mkString(", "))
      .replace("%syntaxObject%",
        if (isQueryDsl) "val " + syntaxName + " = " + className + ".syntax(\"" + syntaxName + "\")" else ""
      )
      .replace("%whereExample%",
        if (isQueryDsl)
          table.primaryKeyColumns.headOption.map { c =>
          "sqls.eq(" + syntaxName + "." + c.nameInScala + ", " + c.defaultValueInScala + ")"
        }.getOrElse("")
        else if (isInterpolation)
          table.primaryKeyColumns.headOption.map { c =>
          "sqls\"" + c.name + " = \\${" + c.defaultValueInScala + "}\""
        }.getOrElse("")
        else
          table.primaryKeyColumns.headOption.map { c =>
            "\"" + c.name + " = {" + c.nameInScala + "}\", '" + c.nameInScala + " -> " + c.defaultValueInScala
          }.getOrElse("")
      )
      .replace("%createFields%", table.allColumns.filter {
        c =>
          c.isNotNull && table.autoIncrementColumns.forall(_.name != c.name)
      }.map {
        c =>
          c.nameInScala + " = " + c.defaultValueInScala
      }.mkString(", "))
  }

}

