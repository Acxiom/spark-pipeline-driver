package com.acxiom.pipeline.steps

import java.nio.file.{Files, Path}

import com.acxiom.pipeline._
import org.apache.commons.io.FileUtils
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.{BeforeAndAfterAll, FunSpec, GivenWhenThen}

import scala.util.Random

class HiveStepsTests extends FunSpec with BeforeAndAfterAll with GivenWhenThen {

  val MASTER = "local[2]"
  val APPNAME = "hive-steps-spark"
  var sparkConf: SparkConf = _
  var sparkSession: SparkSession = _
  var pipelineContext: PipelineContext = _
  val sparkLocalDir: Path = Files.createTempDirectory("sparkLocal")
  val sparkWarehouseDir: Path = Files.createTempDirectory("sparkWarehouse")

  override def beforeAll(): Unit = {
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.apache.hadoop").setLevel(Level.WARN)
    Logger.getLogger("com.acxiom.pipeline").setLevel(Level.DEBUG)
    System.setProperty("derby.system.home", sparkLocalDir.toFile.getAbsolutePath + "/.derby")
    System.setProperty("javax.jdo.option.ConnectionURL", s"jdbc:derby:memory;databaseName=${sparkLocalDir.toFile.getAbsolutePath}/metastore_db;create=true")
    sparkConf = new SparkConf()
      .setMaster(MASTER)
      .setAppName(APPNAME)
      .set("spark.local.dir", sparkLocalDir.toFile.getAbsolutePath)
      .set("spark.sql.warehouse.dir", sparkWarehouseDir.toFile.getAbsolutePath)
    sparkSession = SparkSession.builder().config(sparkConf).getOrCreate()
    pipelineContext = PipelineContext(Some(sparkConf), Some(sparkSession), Some(Map[String, Any]()),
      PipelineSecurityManager(),
      PipelineParameters(List(PipelineParameter("0", Map[String, Any]()), PipelineParameter("1", Map[String, Any]()))),
      Some(List("com.acxiom.pipeline.steps")),
      PipelineStepMapper(),
      Some(DefaultPipelineListener()),
      Some(sparkSession.sparkContext.collectionAccumulator[PipelineStepMessage]("stepMessages")))
    val spark = this.sparkSession
    import spark.implicits._
    Seq(
      (1, "silkie"),
      (2, "buttercup"),
      (3, "leghorn")
    ).toDF("id", "breed").write.saveAsTable("breeds")
  }

  override def afterAll(): Unit = {
    sparkSession.sparkContext.cancelAllJobs()
    sparkSession.sparkContext.stop()
    sparkSession.stop()

    Logger.getRootLogger.setLevel(Level.INFO)
    // cleanup spark directories
    FileUtils.deleteDirectory(sparkLocalDir.toFile)
    FileUtils.deleteDirectory(sparkWarehouseDir.toFile)
  }

  describe("HiveSteps - Basic IO") {
    val chickens = Seq(
      ("1", "silkie"),
      ("2", "polish"),
      ("3", "sultan")
    )

    it("should write a dataFrame to hive"){
      val spark = this.sparkSession
      import spark.implicits._
      val dataFrame = chickens.toDF("id", "chicken")
      HiveSteps.writeDataFrame(dataFrame, "chickens", None)

      val result = spark.sql("select * from chickens")
      assert(result.count() == 3)
    }

    it("should read a dataFrame from hive"){
      val frame = HiveSteps.readDataFrame("breeds", None, pipelineContext)
      assert(frame.count() == 3)
      assert(frame.where("breed = 'leghorn'").count() == 1)
    }

    it("should drop various objects") {
      val length = 10
      val name = "a_" + Random.alphanumeric.take(length).mkString
      val spark = this.sparkSession
      import spark.implicits._
      val dataFrame = chickens.toDF("id", "chicken")
      sparkSession.sql(s"create database $name")
      HiveSteps.writeDataFrame(dataFrame, s"$name.t", None)
      sparkSession.sql(s"create view $name.v AS select chicken from $name.t")
      HiveSteps.drop(s"$name.v", Some("view"), pipelineContext = pipelineContext)
      HiveSteps.drop(s"$name.t", pipelineContext = pipelineContext)
      HiveSteps.drop(s"$name.t", None, Some(true), pipelineContext = pipelineContext)
      HiveSteps.drop(s"$name", Some("schema"), pipelineContext = pipelineContext)
      assert(!sparkSession.sql(s"show databases").collect().map(_.getString(0)).contains(name))
    }

    it("should perform cascade deletions") {
      val length = 10
      val name = "a_" + Random.alphanumeric.take(length).mkString
      val spark = this.sparkSession
      import spark.implicits._
      val dataFrame = chickens.toDF("id", "chicken")
      sparkSession.sql(s"create database $name")
      HiveSteps.writeDataFrame(dataFrame, s"$name.t", None)
      HiveSteps.drop(s"$name", Some("schema"), None, Some(true), pipelineContext = pipelineContext)
      assert(!sparkSession.sql(s"show databases").collect().map(_.getString(0)).contains(name))
    }

    it("should create a managed table") {
      val length = 10
      val name = "a_" + Random.alphanumeric.take(length).mkString
      val dbname = "a_" + Random.alphanumeric.take(length).mkString
      sparkSession.sql(s"create database $dbname")
      val df = createParquetTable(s"$dbname.$name")
      assert(df.columns.length == 1 && df.columns.contains("name"))
      assert(HiveSteps.tableExists(name, Some(dbname), pipelineContext))
      HiveSteps.drop(dbname, Some("schema"), None, Some(true), pipelineContext = pipelineContext)
    }

    it("should check for database existence") {
      val length = 10
      val name = "a_" + Random.alphanumeric.take(length).mkString
      assert(!HiveSteps.databaseExists(name, pipelineContext))
      sparkSession.sql(s"create database $name")
      assert(HiveSteps.databaseExists(name, pipelineContext))
      HiveSteps.drop(name, Some("schema"), None, Some(true), pipelineContext = pipelineContext)
    }

    it("should check for table existence") {
      val length = 10
      val name = "a_" + Random.alphanumeric.take(length).mkString
      assert(!HiveSteps.tableExists(name, pipelineContext = pipelineContext))
      createParquetTable(name)
      assert(HiveSteps.tableExists(name, None, pipelineContext))
      HiveSteps.drop(name, Some("table"), None, Some(false), pipelineContext = pipelineContext)
    }

    it("should check for table existence in a specific database") {
      val length = 10
      val name = "a_" + Random.alphanumeric.take(length).mkString
      val dbname = "a_" + Random.alphanumeric.take(length).mkString
      sparkSession.sql(s"create database $dbname")
      assert(!HiveSteps.tableExists(name, Some(dbname),pipelineContext))
      createParquetTable(s"$dbname.$name")
      assert(HiveSteps.tableExists(name, Some(dbname), pipelineContext))
      HiveSteps.drop(dbname, Some("schema"), None, Some(true), pipelineContext = pipelineContext)
    }

    it("should set the default database") {
      val length = 10
      val name = "a_" + Random.alphanumeric.take(length).mkString
      val dbname = "a_" + Random.alphanumeric.take(length).mkString
      val orig = sparkSession.catalog.currentDatabase
      sparkSession.sql(s"create database $dbname")
      HiveSteps.setCurrentDatabase(dbname, pipelineContext)
      createParquetTable(name)
      assert(HiveSteps.tableExists(name, Some(dbname), pipelineContext))
      HiveSteps.setCurrentDatabase(orig, pipelineContext)
      HiveSteps.drop(dbname, Some("schema"), None, Some(true), pipelineContext = pipelineContext)
    }
  }

  private def createParquetTable(name: String): DataFrame = {
    val options = DataFrameReaderOptions(schema = Some(Schema(List(Attribute("name", "String")))))
    HiveSteps.createTable(name, None, Some(options), pipelineContext = pipelineContext)
  }
}
