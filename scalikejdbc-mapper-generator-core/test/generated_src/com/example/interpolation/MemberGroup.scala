package com.example.interpolation

import scalikejdbc._
import scalikejdbc.SQLInterpolation._

case class MemberGroup(
  id: Int, 
  name: String, 
  underscore: Option[String] = None) {

  def save()(implicit session: DBSession = MemberGroup.autoSession): MemberGroup = MemberGroup.save(this)(session)

  def destroy()(implicit session: DBSession = MemberGroup.autoSession): Unit = MemberGroup.destroy(this)(session)

}
      

object MemberGroup extends SQLSyntaxSupport[MemberGroup] {

  override val tableName = "MEMBER_GROUP"

  override val columns = Seq("ID", "NAME", "_UNDERSCORE")

  def apply(mg: ResultName[MemberGroup])(rs: WrappedResultSet): MemberGroup = new MemberGroup(
    id = rs.int(mg.id),
    name = rs.string(mg.name),
    underscore = rs.stringOpt(mg.underscore)
  )

  def apply(mg: MemberGroup)(rs: WrappedResultSet): MemberGroup = apply(mg.resultName)(rs)
      
  val mg = MemberGroup.syntax("mg")

  override val autoSession = AutoSession

  def find(id: Int)(implicit session: DBSession = autoSession): Option[MemberGroup] = {
    sql"""select ${mg.result.*} from ${MemberGroup as mg} where ID = \${mg.id}"""
      .map(MemberGroup(mg.resultName)).single.apply()
  }
          
  def findAll()(implicit session: DBSession = autoSession): List[MemberGroup] = {
    sql"""select ${mg.result.*} from ${MemberGroup as mg}""".map(MemberGroup(mg.resultName)).list.apply()
  }
          
  def countAll()(implicit session: DBSession = autoSession): Long = {
    sql"""select count(1) from ${MemberGroup.table}""".map(rs => rs.long(1)).single.apply().get
  }
          
  def findAllBy(where: SQLSyntax)(implicit session: DBSession = autoSession): List[MemberGroup] = {
    sql"""select ${mg.result.*} from ${MemberGroup as mg} where ${where}""" 
      .map(MemberGroup(mg.resultName)).list.apply()
  }
      
  def countBy(where: SQLSyntax)(implicit session: DBSession = autoSession): Long = {
    sql"""select count(1) from ${MemberGroup as mg} where ${where}"""
      .map(_.long(1)).single.apply().get
  }
      
  def create(
    name: String,
    underscore: Option[String] = None)(implicit session: DBSession = autoSession): MemberGroup = {
    val generatedKey = sql"""
      insert into ${MemberGroup.table} (
        ${column.name},
        ${column.underscore}
      ) values (
        ${name},
        ${underscore}
      )
      """.updateAndReturnGeneratedKey.apply()

    MemberGroup(
      id = generatedKey.toInt, 
      name = name,
      underscore = underscore)
  }

  def save(entity: MemberGroup)(implicit session: DBSession = autoSession): MemberGroup = {
    sql"""
      update
        ${MemberGroup.table}
      set
        ${column.id} = ${entity.id},
        ${column.name} = ${entity.name},
        ${column.underscore} = ${entity.underscore}
      where
        ${column.id} = ${entity.id}
      """.update.apply()
    entity 
  }
        
  def destroy(entity: MemberGroup)(implicit session: DBSession = autoSession): Unit = {
    sql"""delete from ${MemberGroup.table} where \${column.id} = \${entity.id}""".update.apply()
  }
        
}
