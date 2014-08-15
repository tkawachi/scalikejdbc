package scalikejdbc

import org.scalatest._

class SQLSyntaxSupportFeatureSpec extends FlatSpec with Matchers with SQLInterpolation with DBSettings {

  behavior of "SQLSyntaxSupportFeature"

  it should "verify table name" in {
    SQLSyntaxSupportFeature.verifyTableName("foo.bar")
    SQLSyntaxSupportFeature.verifyTableName(" foo.bar ")
    SQLSyntaxSupportFeature.verifyTableName("foo bar")
    SQLSyntaxSupportFeature.verifyTableName("foo;bar")
  }

  behavior of "SQLSyntaxSupport"

  case class SystemTables(id: Int)
  object SystemTables extends SQLSyntaxSupport[SystemTables]

  it should "retrieve columns" in {

    DB autoCommit { implicit session =>
      // In HSQLDB, SYSTEM_TABLES is a table in INFORMATION_SCHEMA.
      // Here we create a table with a same name, and try to retrieve columns.
      SQL("CREATE TABLE SYSTEM_TABLES(ID INT)").execute().apply()

      SystemTables.columns should equal(Seq("id"))
    }
  }

}
