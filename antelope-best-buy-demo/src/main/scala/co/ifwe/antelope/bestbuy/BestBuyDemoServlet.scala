package co.ifwe.antelope.bestbuy

import java.io.File
import java.text.SimpleDateFormat

import co.ifwe.antelope.{EventSource, Event}
import co.ifwe.antelope.bestbuy.event.{ProductUpdate, ProductView}
import org.scalatra._

import org.json4s.{DefaultFormats, Formats}

import org.scalatra.json._

import org.slf4j.{LoggerFactory}

import scala.collection.mutable

class BestBuyDemoServlet extends AntelopeBestBuyDemoStack with JacksonJsonSupport {
  val logger = LoggerFactory.getLogger(getClass)

  protected implicit val jsonFormats: Formats = DefaultFormats.withBigDecimal

  // TODO lots of duplication to remove here
  val dataDir = System.getenv("ANTELOPE_DATA")
  if (dataDir == null || dataDir.isEmpty) {
    throw new IllegalArgumentException("must set $ANTELOPE_DATA environment variable")
  }
  val trainingDir = System.getenv("ANTELOPE_TRAINING")
  if (trainingDir == null || trainingDir.isEmpty) {
    throw new IllegalArgumentException("must set $ANTELOPE_TRAINING environment variable")
  }

  val viewsFn = dataDir + File.separator + "train.csv"
  val productsFn = dataDir + File.separator + "small_product_data.xml"

  val catalog = mutable.HashMap[Long, ProductUpdate]()

  val ep = new ModelEventProcessor(
    weights = Array(87.48098,3013.327,0.1203471,4506.025,-0.02656143),
    progressPrintInterval = 500) {
    override protected def consume(e: Event) = {
      e match {
        case pu: ProductUpdate =>
          catalog += pu.sku -> pu
        case _ =>
      }
      super.consume(e)
    }
  }

  ep.start()
  ep.process(ProductsReader.fromFile(productsFn).map(ProductUpdate(0L,_)))
  val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
  val backupDf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  def getTime(timeStr: String): Long = {
    try {
      df.parse(timeStr).getTime
    } catch {
      case e: java.text.ParseException => backupDf.parse(timeStr).getTime
    }
  }
  ep.process(EventSource.fromFile(viewsFn).map(e =>
    new ProductView(getTime(e("click_time")), e("query"), e("sku").toLong)), 20000)


  case class Query(query: String)
  case class Results(success: Boolean, data: Array[Long])
  case class ProductDescription(sku: Long, title: String)
  case class ProductDetailedDescription(sku: Long, title: String, description: String)
  case class ResultsWithTitles(success: Boolean, results: Array[ProductDescription])

  private def buildResults(results: Array[Long]) : ResultsWithTitles = {
    new ResultsWithTitles(success = true,
      results = results.map(sku => ProductDescription(sku, catalog(sku).name)))
  }

  before() {
    contentType = formats("json")
  }

  get("/details") {
    val sku = params("sku").toLong
    logger.info(s"getting detail for $sku")
    val desc = catalog(sku)
    new ProductDetailedDescription(sku, desc.name, desc.description)
  }

  post("/search") {
    val query = params("query")
    logger.info(s"doing a search for '$query'")
    val results = ep.topDocs(query, 10)
    buildResults(results)
  }
  
}