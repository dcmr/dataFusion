package au.csiro.data61.dataFusion.tika

import org.scalatest.{ FlatSpec, Matchers }

import com.typesafe.scalalogging.Logger

class TikaTest extends FlatSpec with Matchers {
  private val log = Logger(getClass)
  val tikaUtil = new TikaUtil(Main.defaultCliOption)
  
  "Tika" should "extract 1 page of PDF" in {
    val path = "/exampleData/PDF002.pdf" // born digital, has logo image with no text
    val docIn = tikaUtil.tika(getClass.getResourceAsStream(path), path, 0L)
    // log.debug(s"docIn = ${docIn}")
    docIn.content.map(_.size).getOrElse(0) > 100 should be(true) // born digital text
    docIn.embedded.size should be(1) // has 1 embedded doc - the logo

//    log.debug(s"content = ${docIn.embedded(0).content}")
//    docIn.embedded(0).content.isDefined should be(false) // for which we get no text
    // we got content = None with tesseract3 but Some with tesseract4, so commented out this bit
  }
  
  it should "extract 5 pages of PDF" in {
    val path = "/exampleData/PDF003.pdf" // scanned doc
    val docIn = tikaUtil.tika(getClass.getResourceAsStream(path), path, 0L)
    // log.debug(s"docIn = ${docIn}")
    docIn.content.map(_.size).getOrElse(0) > 100 should be(true) // text OCR by scanner
    docIn.embedded.size should be(5) // 5 embedded page images
    docIn.embedded.foreach(_.content.map(_.size).getOrElse(0) > 100 should be(true)) // tesseract got text from each page
  }
  
  it should "extract from good Excel" in {
    val path = "/exampleData/xls001.xls"
    val d = tikaUtil.tika(getClass.getResourceAsStream(path), path, 0L)
    // log.debug(s"d = $d")
    d.content.get.contains("Principality of Liechtenstein") should be(true)
    d.meta.get("Content-Type") should be(Some("application/vnd.ms-excel"))
  }
  
  it should "convert good Excel to opendocument.spreadsheet (only when explicitly asked to) and extract" in {
    val path = "/exampleData/xls001.xls"
    val d = tikaUtil.convertAndParseDoc(getClass.getResourceAsStream(path), path, 0L)
    // log.debug(s"d = $d")
    d.content.get.contains("Principality of Liechtenstein") should be(true)
    d.meta.get("Content-Type") should be(Some("application/vnd.oasis.opendocument.spreadsheet"))
  }
    
  it should "convert bad Excel to opendocument.spreadsheet (when not explicitly asked to) and extract" in {
    // test Excel file is attachment from: https://bz.apache.org/bugzilla/show_bug.cgi?id=57104
    val path = "/exampleData/data-prob-2-12.XLS"
    val d = tikaUtil.tika(getClass.getResourceAsStream(path), path, 0L)
    // log.debug(s"d = $d")
    d.content.get.contains("562.03") should be(true)
    d.meta.get("Content-Type") should be(Some("application/vnd.oasis.opendocument.spreadsheet"))
  }
  
}