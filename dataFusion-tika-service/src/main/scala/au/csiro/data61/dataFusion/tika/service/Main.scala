package au.csiro.data61.dataFusion.tika.service

import java.io.ByteArrayInputStream

import scala.concurrent.{ ExecutionContext, Future }
import scala.language.postfixOps
import scala.reflect.runtime.universe.typeOf
import scala.util.{ Failure, Success, Try }

import com.github.swagger.akka.{ HasActorSystem, SwaggerHttpService }
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.{ ToResponseMarshallable, ToResponseMarshaller }
import akka.http.scaladsl.model.{ StatusCode, StatusCodes }
import akka.http.scaladsl.model.HttpMethods.PUT
import akka.http.scaladsl.server.Directives._
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.Source
import akka.util.ByteString
import au.csiro.data61.dataFusion.common.Data.Doc
import au.csiro.data61.dataFusion.common.Data.JsonProtocol.docFormat
import au.csiro.data61.dataFusion.tika.TikaUtil
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import io.swagger.annotations.{ Api, ApiOperation, ApiParam, ApiResponses, ApiResponse }
import javax.ws.rs.{ Consumes, Path, QueryParam }
import resource.managed
import java.util.concurrent.atomic.AtomicLong
import org.apache.tika.parser.ocr.TesseractOCRParser
import au.csiro.data61.dataFusion.tika.Main.{ CliOption, defaultCliOption, initSystemProperties }

object Main {
  private val log = Logger(getClass)
  
  @Api(value = "tika", description = "tika service", produces = "application/json")
  @Path("")
  class TikaService(cliOption: CliOption)(implicit materializer: Materializer, val executionContext: ExecutionContext)  {
    val tikaUtil = new TikaUtil(cliOption)
    val id = new AtomicLong(cliOption.startId)
    
//    // isn't getting executed
//    sys.addShutdownHook {
//      log.info(s"TikaService: Shutting down: next id would be ${id.get}")
//    }

    /** if Success(a) marshall the a, else marshall the orElse */
    def tryOrElse[A](x: Future[Try[A]], orElse: Throwable => (StatusCode, String))(implicit m: ToResponseMarshaller[A]): Future[ToResponseMarshallable] = {
      x.map {
        case Success(a) =>
          ToResponseMarshallable(a)
        case Failure(t) =>
          log.error(s"tika error", t)
          ToResponseMarshallable(orElse(t))
      }
    }
    
    /** convert a binary akka stream, src, to an Array[Byte] containing the whole stream */
    def toBytes(src: Source[ByteString, Any]): Future[Array[Byte]] = {
      src.runFold(new Array[Byte](0)) { (acc, bs) =>
        val b = bs.toArray
        acc ++ b
      }
    }
  
    // supports:
    //   curl --upload-file PDF002.pdf http://${host}:${port}/tika?path=PDF002.pdf
    // or equivalently:
    //   curl -X PUT http://${host}:${port}/tika?path=PDF002.pdf --data-binary @PDF002.pdf
    //
    // Use: --max-time 300 if necessary to increase timeout for tesseract processing
    //
    // These headers are correct but not required:
    // --header "accept: application/json" --header "content-type: application/octet-stream"
    @Path("tika")
    @ApiOperation(httpMethod = "PUT", response = classOf[Doc], value = "extract document text and metadata")
    @Consumes(Array("application/octet-stream"))
    @ApiResponses(Array(
      new ApiResponse(code = 500, message = "tika error")
    ))
    def tika(
      @ApiParam(value = "path may be used as a hint for the data format", required = true) @QueryParam("path") path: String, 
      data: Array[Byte]
    ): Try[Doc] = {
      Try { tikaUtil.tika(new ByteArrayInputStream(data), path, id.getAndIncrement) } // stream opened/closed in parseTextMeta
    }
    
    def tikaRoute = put { path("tika") { parameters("path") { path => { extractDataBytes { src =>
      complete {
        tryOrElse(
          toBytes(src).map { data => tika(path, data) }, 
          t => (StatusCodes.InternalServerError, s"${t.getClass.getName}: ${t.getMessage}")
        )
      }
    }}}}}
                  
    val routes = tikaRoute
  }
  
  def swaggerService(hst: String, prt: Int)(implicit s: ActorSystem, m: ActorMaterializer) = new SwaggerHttpService with HasActorSystem {
    override implicit val actorSystem = s
    override implicit val materializer = m
    override val apiTypes = Seq(typeOf[TikaService])
    override def swaggerConfig = new io.swagger.models.Swagger().basePath(prependSlashIfNecessary(basePath)) // don't specify protocol://host basePath
//    override val host = s"${hst}:${prt}" // the url of your api, not swagger's json endpoint
//    override val basePath = "/"          // the basePath for the API you are exposing
    override val info = new io.swagger.models.Info()                    // provides license and other description details
    override val apiDocsPath = "api-docs"   // http://host:port/api-docs/swagger.json
  }
  
  def start(cliOption: CliOption) = {
    TesseractOCRParser.ocrImagePreprocess = cliOption.ocrImagePreprocess
    TesseractOCRParser.ocrImageDeskew = cliOption.ocrImageDeskew
    
    val conf = ConfigFactory.load
    val host = conf.getString("http.host")
    val port = conf.getInt("http.port")
  
    implicit val system = ActorSystem("dbActorSystem")
    implicit val exec = system.dispatcher
    implicit val materializer = ActorMaterializer()
    
    val settings = {
      val s = CorsSettings.defaultSettings
      s.copy(allowedMethods = PUT +: s.allowedMethods)
    }
    val routes = cors(settings) {
      (new TikaService(cliOption)).routes ~ 
      swaggerService(host, port).routes
    }
    Http().bindAndHandle(routes, host, port)
    log.info(s"""starting server at: http://${host}:${port}
Test with:
  curl http://${host}:${port}/api-docs/swagger.json
  curl --max-time 300 --upload-file dataFusion-tika/src/test/resources/exampleData/PDF002.pdf http://${host}:${port}/tika?path=PDF002.pdf
""")
  }
  
  def main(args: Array[String]): Unit = {
    initSystemProperties
    
    val parser = new scopt.OptionParser[CliOption]("dataFusion-tika-service") {
      head("dataFusion-tika-service", "0.x")
      note("Tika text and metadata extraction web service.")
      opt[Long]("startId") action { (v, c) =>
        c.copy(startId = v)
      } text (s"id's allocated incrementally starting with this value, (default ${defaultCliOption.startId})")
      // CliOption.numWorkers not used
      opt[String]("pdfOcrStrategy") action { (v, c) =>
        c.copy(pdfOcrStrategy = v)
      } text (s"pdfOcrStrategy = no_ocr|ocr_only|ocr_and_text, (default ${defaultCliOption.pdfOcrStrategy}). no_ocr means use the text in the PDF, but still OCR embedded images. ocr_only means render the whole page (text and images) as an image and OCR that, otherwise ignoring the text in the PDF.")
      opt[Boolean]("pdfExtractInlineImages") action { (v, c) =>
        c.copy(pdfExtractInlineImages = v)
      } text (s"whether to extract and OCR inline images in PDF, (default ${defaultCliOption.pdfExtractInlineImages})")
      opt[Boolean]("ocrImagePreprocess") action { (v, c) =>
        c.copy(ocrImagePreprocess = v)
      } text (s"whether to preprocess images with ImageMagik prior to OCR, (default ${defaultCliOption.ocrImagePreprocess})")
      opt[Boolean]("ocrImageDeskew") action { (v, c) =>
        c.copy(ocrImageDeskew = v)
      } text (s"whether to determine image skew using rotation.py so ImageMagik can deskew (can be very slow, default ${defaultCliOption.ocrImageDeskew})")
      opt[Int]("ocrTimeout") action { (v, c) =>
        c.copy(ocrTimeout = v)
      } text (s"ocr timeout secs, (default ${defaultCliOption.ocrTimeout})")
      opt[Int]("ocrResize") action { (v, c) =>
        c.copy(ocrResize = v)
      } text (s"resize image to ocrResize% of original prior to OCR (too large can be very slow, default ${defaultCliOption.ocrResize})")
      opt[Boolean]("ocrPreserveInterwordSpacing") action { (v, c) =>
        c.copy(ocrPreserveInterwordSpacing = v)
      } text (s"whether OCR should preserve interword spacing, (default ${defaultCliOption.ocrPreserveInterwordSpacing})")
      help("help") text ("prints this usage text")
    }
    
    parser.parse(args, defaultCliOption) foreach start    
  }
  
}