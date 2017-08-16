package au.csiro.data61.dataFusion.tika

import java.io.InputStream

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.reflect.ClassManifestFactory.classType
import scala.util.{ Failure, Try }
import scala.util.control.NonFatal

import org.apache.tika.config.TikaConfig
import org.apache.tika.detect.Detector
import org.apache.tika.exception.TikaException
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{ Metadata, TikaMetadataKeys }
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.{ AutoDetectParser, ParseContext, Parser, RecursiveParserWrapper }
import org.apache.tika.parser.html.HtmlParser
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.sax.BasicContentHandlerFactory
import org.apache.tika.sax.BasicContentHandlerFactory.HANDLER_TYPE
import org.xml.sax.ContentHandler

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import au.csiro.data61.dataFusion.common.Data._
import resource.managed
import org.apache.tika.parser.pdf.PDFParserConfig.OCR_STRATEGY
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.io.IOUtils
import scala.language.postfixOps
import java.io.FileInputStream
import java.io.FileNotFoundException
import scala.collection.mutable.Buffer
import scala.collection.mutable.ListBuffer

object TikaUtil {
  private val log = Logger(getClass)

  val conf = ConfigFactory.load
  
  // modified from org.apache.tika.server.resource.TikaResource
  def createParser: AutoDetectParser = {
    val p = new AutoDetectParser(TikaConfig.getDefaultConfig)
    
    val ps = p.getParsers
    ps.put(MediaType.APPLICATION_XML, new HtmlParser)
    p.setParsers(ps)
    
    p.setFallback(new Parser() {
      override def getSupportedTypes(c: ParseContext): java.util.Set[MediaType] = p.getSupportedTypes(c)
      override def parse(in: InputStream, ch: ContentHandler, m: Metadata, c: ParseContext): Unit =
        throw new Exception("UNSUPPORTED_MEDIA_TYPE")
    })
    
    p
  }

  // modified from org.apache.tika.server.resource.TikaResource
  def fillMetadata(parser: AutoDetectParser, metadata: Metadata, fileName: String): Unit = {
    metadata.set(TikaMetadataKeys.RESOURCE_NAME_KEY, fileName)
    val prev = parser.getDetector
    parser.setDetector(new Detector {
      override def detect(inputStream: InputStream, metadata: Metadata): MediaType = {
        Option(metadata.get(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE))
          .flatMap(ct => Option(MediaType.parse(ct)))
          .getOrElse(prev.detect(inputStream, metadata))
      }
    })
  }
  
	val context = {
	  val ocrConf = { 
	    val c = new TesseractOCRConfig
	    val timeout = conf.getInt("tika.timeout")
  	  log.info(s"context: changing ocr timeout from ${c.getTimeout} sec, to $timeout sec")
  	  c.setTimeout(timeout)
  	  c
	  }
	  val pdfConf = {
	    val c = new PDFParserConfig
	    // TODO: add command line overrides for tika options?
//	    c.setExtractInlineImages(false)
//	    c.setSortByPosition(true)
//	    c.setOcrStrategy(OCR_STRATEGY.NO_OCR)
	    c
	  }
	  val c = new ParseContext
    c.set(classOf[TesseractOCRConfig], ocrConf)
    c.set(classOf[PDFParserConfig], pdfConf)
	  c
	}
    
  /**
   * modified from  org.apache.tika.server.resource.RecursiveMetadataResource
   */
  def parseMetadata(is: InputStream, fileName: String): Try[List[Metadata]] = Try {
		val metadata = new Metadata		
		val parser = createParser
		fillMetadata(parser, metadata, fileName)
		
		val wrapper = new RecursiveParserWrapper(parser, new BasicContentHandlerFactory(HANDLER_TYPE.TEXT, -1))
		for(tikaInputStream <- managed(TikaInputStream.get(is)))
      wrapper.parse(tikaInputStream, null, metadata, context)
		
    wrapper.getMetadata.asScala.toList
  }
  
  /**
   * tika/pdfbox extracts lots of bodgy char > 61000 decimal from RHC-Annual-Report-2016.pdf
   * so filter out anything >= 256 with a warning
   * TODO: maybe add embIdx (page for scanned docs) to logged warning?
   */
  def cleanText(fileName: String, t: Option[String]) = {
    val s = t.map(_.filter(_.toInt < 256))
    if (s.isDefined) {
      val n = t.get.length - s.get.length
      if (n > 0) log.warn(s"cleanText: path: $fileName, filtered ${n} or ${n*100.0/t.get.length}% chars >= 256")
    }
    s
  }
    
  /** we're getting metadata with key='Comments' and val='LEAD Technologies Inc. V1.01\0' (where the \0 is a null byte) */
  def stripTrailingNull(s: String) = if (s.nonEmpty && s.last == '\u0000') s.take(s.length - 1) else s

  /** convert from Tika data struct to ours, add language metadata, with a little metadata filtering and cleaning */
  def toEmbedded(fileName: String)(m: Metadata): Embedded = {
    // we pull X-TIKA:content out of metadata and exclude some metadata from images that is large and not likely to be useful to us
    val largeNotUseful = Set("X-TIKA:content", "Chroma Palette PaletteEntry", "LocalColorTable ColorTableEntry", "Strip Byte Counts", "Strip Offsets", "PLTE PLTEEntry")

    val content = cleanText(fileName, Option(m.get("X-TIKA:content")))
    // TODO: if Spanish we should repeat the OCR  telling tesseract its Spanish or use -l eng+esp ?
    val langMeta = content.flatMap(LangDetect.lang).map(l => Map(META_LANG_CODE -> l.lang, "language-prob" -> l.prob.toString)).getOrElse(Map.empty)
    
    val meta = langMeta ++ (for {
      key <- m.names.view if !largeNotUseful.contains(key)
      vs <- Option(m.getValues(key))
      v = vs.map(stripTrailingNull).mkString("; ") if v.nonEmpty
    } yield key -> v)
    
    Embedded(content, meta, List.empty)
  }
  
  /** parse `in` to produce a Doc */
  def parseDoc(fileName: String, id: Long)(in: InputStream): Try[Doc] = parseMetadata(in, fileName).map { l =>
    val (x :: xs) = l.map(toEmbedded(fileName)) 
    Doc(id, x.content, x.meta, fileName, List.empty, xs)
  }
    
  /** convert spreadsheet `in` to Open Document Spreadsheet format and parse that instead of `in` to produce a Doc
   *  (use after parsing original spreadsheet has failed)
   */
  def convertAndParseDoc(inCtor: => InputStream, fileName: String, id: Long): Try[Doc] = {
    val outFile = Try {
      import scala.sys.process._
      
      def dir(p: String) = {
        val d = new File(p)
        d.mkdir
        d
      }
      val (inDir, outDir) = (dir("/tmp/tikaIn"), dir("/tmp/tikaOut"))

      // copy inCtor to inFile
      val inFile = File.createTempFile("convert", "", inDir)
      for {
        in <- managed(inCtor)
        out <- managed(new FileOutputStream(inFile))
      } IOUtils.copy(in, out)
        
      // convert inFile to outFile
      // https://stackoverflow.com/questions/22062973/libreoffice-convert-to-not-working
      val outBuf = new ListBuffer[String]
      val errBuf = new ListBuffer[String]
      val env = "-env:UserInstallation=file:///$HOME/.libreoffice-headless/" // TODO: check LibreOffice version between 4.5 and 5.3
      val exitCode = s"soffice $env --convert-to ods --outdir ${outDir} ${inFile}" ! ProcessLogger(outBuf +=, errBuf +=)
      log.info(s"convertAndParseDoc: soffice exit code = $exitCode, stdout = ${outBuf.mkString("\n")}, stderr = ${errBuf.mkString("\n")}") // says 0 even on error
      inFile.delete
      val f = new File(outDir, s"${inFile.getName}.ods")
      if (!f.canRead) throw new FileNotFoundException(s"conversion from $fileName to Open Document Spreadsheet format failed")
      f
    }
    
    // run parse on outFile
    outFile flatMap { f => 
      val d = managed(new FileInputStream(f)) acquireAndGet parseDoc(fileName, id)
      f.delete
      d
    }
  }
    
  /** run Tika to produce a Doc (Failure for unparsable input, timeout etc.)
   *  `in` is passed as a thunk/function so that the stream can be opened more than once if necessary.
   *  This function closes each stream it opens.
   *  Case where it opens `in` more than once: if there is an exception from parsing an Excel spreadsheet
   *  we convert it to Open Document Spreadsheet format and parse that instead (because the OpenOffice
   *  conversion often succeeds on Excel files that Tika cannot parse).
   *  Tesseract OCR of scanned PDFs gets text from different lines mixed up if there is too much skew.
   *  TODO: Pre-processing to descew images can greatly improve OCR results. Is it needed?
   *  ocrmypdf does descrew but timeouts on tesseract 4, perhaps due to old versions of ghostscript in ubuntu repo.
   */
  def tika(inCtor: => InputStream, fileName: String, id: Long): Try[Doc] = {
    
    managed(inCtor) acquireAndGet parseDoc(fileName, id) recoverWith {
      case e: TikaException =>
        // Exceptions seen so far are:
        //   org.apache.poi.hssf.record.RecordInputStream$LeftoverDataException:
        //   Initialisation of record 0x23(ExternalNameRecord) left 22 bytes remaining still to be read.
        // and:
        //   org.apache.poi.hssf.record.RecordFormatException:
        //   The content of an excel record cannot exceed 8224 bytes
        // Closest common ancestor is java.lang.RuntimeException, hence matching on the package name ...
        if (e.getCause.getClass.getName.startsWith("org.apache.poi.hssf.record")) {
          log.warn(s"attempting recovery from POI exception on path: $fileName", e)
          convertAndParseDoc(inCtor, fileName, id)
        } else new Failure(e)
    } recoverWith {
      case NonFatal(e) => new Failure(new TikaException(s"error processing path: $fileName", e))
    }
  }
}
