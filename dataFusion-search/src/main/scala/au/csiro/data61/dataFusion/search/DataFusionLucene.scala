package au.csiro.data61.dataFusion.search

import java.io.{ File, FileInputStream, InputStreamReader }
import java.nio.charset.Charset

import scala.collection.JavaConverters.{ asScalaBufferConverter, mapAsJavaMapConverter }
import scala.collection.mutable.ListBuffer
import scala.language.implicitConversions

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.core.{ FlattenGraphFilter, KeywordAnalyzer, WhitespaceTokenizer }
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.synonym.{ SolrSynonymParser, SynonymGraphFilter }
import org.apache.lucene.document.{ BinaryDocValuesField, Document, Field, FieldType }
import org.apache.lucene.index.{ IndexOptions, IndexReader, IndexWriter, IndexWriterConfig, PostingsEnum, Term }
import org.apache.lucene.search.{ DocIdSetIterator, IndexSearcher, ScoreDoc, SimpleCollector, TermQuery }
import org.apache.lucene.search.spans.{ SpanCollector, SpanNearQuery, SpanTermQuery, SpanWeight, Spans }
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import LuceneUtil.{ TrailingPunctuationFilter, tokenIter }
import au.csiro.data61.dataFusion.common.Data.{ DHits, IdEmbIdx, LDoc, LMeta, LNer, LPosDoc, MHits, NHits, PHits, PosInfo, PosQuery, Stats, T_ORGANIZATION }
import au.csiro.data61.dataFusion.common.Data.JsonProtocol._
import au.csiro.data61.dataFusion.common.Timer
import spray.json.{ pimpAny, pimpString }

import resource.managed

/**
 * dataFusion specific field names, analyzers etc. for Lucene.
 */
object DataFusionLucene {
  private val log = Logger(getClass)
  
  val utf8 = Charset.forName("UTF-8")
  val conf = ConfigFactory.load.getConfig("search")
  
  val Seq(docIndex, metaIndex, nerIndex) = 
    Seq("docIndex", "metaIndex", "nerIndex").map(k => new File(conf.getString(k)))

  /** field names */
  
  val F_ID_EMB_IDX = "idEmbIdx" // for a DocValues, so we can fetch IdEmbIdx without loading the Lucene Document
  
  val F_ID = "id"
  val F_EMB_IDX = "embIdx"
  val F_JSON = "json"
  
  val F_CONTENT = "content"
  val F_PATH = "path"

  val F_KEY = "key"
  val F_VAL = "val"
  
  val F_TEXT = "text"
  val F_TYP = "typ"
  val F_IMPL = "impl"
  
  /** Create SynonymMap by reading a file in Solr synonym format.
   *  See: https://lucene.apache.org/core/6_6_0/analyzers-common/org/apache/lucene/analysis/synonym/SolrSynonymParser.html
   */
  val synonyms = {
    val synAnalyzer = new Analyzer {
      override protected def createComponents(fieldName: String): TokenStreamComponents = {
        val src = new StandardTokenizer
        val lcf = new LowerCaseFilter(src)
        new TokenStreamComponents(src, lcf) 
      }
    }
    val parser = new SolrSynonymParser(true, false, synAnalyzer); // bools are dedup, expand
    for (in <- managed(new InputStreamReader(new FileInputStream(conf.getString("synonyms")), utf8))) {
      parser.parse(in)
    }
    parser.build
  }
  
  // for metadata values & named entity mention text, we don't want stop words or stemming, just lower casing
  val lowerAnalyzer = new Analyzer {
    override protected def createComponents(fieldName: String): TokenStreamComponents = {
      val src = new StandardTokenizer
      val f1 = new LowerCaseFilter(src)
      new TokenStreamComponents(src, f1)
    }
  }
  // for doc content, we don't want stop words or stemming, just lower casing and synonyms
  // TODO: may need another version without FlattenGraphFilter for queries?
  val synonymAnalyzer = new Analyzer {
    override protected def createComponents(fieldName: String): TokenStreamComponents = {
      val src = new WhitespaceTokenizer
      val f0 = new TrailingPunctuationFilter(src)
      val f1 = new LowerCaseFilter(f0)
      val f2 = new SynonymGraphFilter(f1, synonyms, false)
      val f3 = new FlattenGraphFilter(f2)
      new TokenStreamComponents(src, f3)
    }
  }
  val kwAnalyzer = new KeywordAnalyzer
  val analyzer = new PerFieldAnalyzerWrapper(kwAnalyzer, Map(F_CONTENT -> synonymAnalyzer, F_VAL -> lowerAnalyzer, F_TEXT -> lowerAnalyzer).asJava)
    
  object DFIndexing {
    val indexedKeywordType = {
      val t = new FieldType
      t.setIndexOptions(IndexOptions.DOCS)
      t.setTokenized(false)
      t.setStored(false)
      t.freeze();
      t
    }
    
    val jsonType = {
      val t = new FieldType
      t.setIndexOptions(IndexOptions.NONE)
      t.setTokenized(false)
      t.setStored(true)
      t.freeze();
      t
    }
      
    val contentType = {
      val t = new FieldType
      // based on TextField
      t.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)
      t.setTokenized(true)
      t.setStored(false)
      // PosDocSearch no longer need term vectors
//      t.setStoreTermVectors(true)
//      t.setStoreTermVectorPositions(true) // token positions (word index/offset)
//      t.setStoreTermVectorOffsets(true) // token character index/offset
      t.freeze();
      t
    }
      
//    val pathType = {
//      val t = new FieldType
//      t.setIndexOptions(IndexOptions.DOCS)
//      t.setTokenized(true)
//      t.setStored(false)
//      t.freeze();
//      t
//    }
      
    val nerTextType = {
      val t = new FieldType
      // based on TextField
      t.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)
      t.setTokenized(true)
      t.setStored(false)
      t.freeze();
      t
    }
    val metaTextType = nerTextType
          
    val fieldType = Map(
      F_ID -> indexedKeywordType,
      F_EMB_IDX -> indexedKeywordType,
      F_JSON -> jsonType,
      F_CONTENT -> contentType,
      F_PATH -> indexedKeywordType,
      
      F_KEY -> indexedKeywordType,
      F_VAL -> metaTextType,
      
      F_TEXT -> nerTextType,
      F_TYP -> indexedKeywordType,
      F_IMPL -> indexedKeywordType
    )
  
    class DocBuilder {
      val d = new Document
      
      def add(f: String, v: String): DocBuilder = {
        d.add(new Field(f, v, fieldType(f)))
        this
      }
      
      def addBinaryDocValue(f: String, v: String): DocBuilder = {
        d.add(new BinaryDocValuesField(f, new BytesRef(v)))
        this
      }
      
      def get = d
    }
    
    object DocBuilder {
      def apply() = new DocBuilder
    }
    
    implicit def ldoc2doc(x: LDoc): Document = DocBuilder()
      .addBinaryDocValue(F_ID_EMB_IDX, x.idEmbIdx.toJson.compactPrint)
      .add(F_ID, x.idEmbIdx.id.toString)
      .add(F_EMB_IDX, x.idEmbIdx.embIdx.toString)
      .add(F_JSON, x.toJson.compactPrint)
      .add(F_CONTENT, x.content)
      .add(F_PATH, x.path)
      .get
    
    implicit def lmeta2doc(x: LMeta): Document = DocBuilder()
      .add(F_ID, x.idEmbIdx.id.toString)
      .add(F_EMB_IDX, x.idEmbIdx.embIdx.toString)
      .add(F_JSON, x.toJson.compactPrint)
      .add(F_KEY, x.key)
      .add(F_VAL, x.`val`)
      .get
      
    implicit def lner2doc(x: LNer): Document = DocBuilder()
      .add(F_ID, x.idEmbIdx.id.toString)
      .add(F_EMB_IDX, x.idEmbIdx.embIdx.toString)
      .add(F_JSON, x.toJson.compactPrint)
      .add(F_IMPL, x.impl)
      .add(F_TYP, x.typ)
      .add(F_TEXT, x.text)
      .get
    
    def mkIndexer(dir: Directory) = new IndexWriter(
      dir,
      new IndexWriterConfig(analyzer)
        .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
    )
  }
  
  object DFSearching {
    
    def ldoc(doc: Document) = doc.get(F_JSON).parseJson.convertTo[LDoc]
     
    object DocSearch {
      def toHit(scoreDoc: ScoreDoc, doc: Document) = {
        val d = ldoc(doc)
        (scoreDoc.score, d)
       }
      
      def toResult(totalHits: Int, elapsedSecs: Float, hits: Seq[(Float, LDoc)], error: Option[String])
        = DHits(Stats(totalHits, elapsedSecs), hits.toList, error)
    }
    
    object PosDocSearch {
      class MyCollector(reader: IndexReader, t: Term, score: Double) extends SimpleCollector {
        // val fieldsToLoad = java.util.Collections.singleton(F_CONTENT)
        private var pe: PostingsEnum = null
        private val hitBuf = ListBuffer[LPosDoc]()
        private val posInfos = ListBuffer[PosInfo]()
        
        override def needsScores = false
        
        override def collect(doc: Int): Unit = {
          posInfos.clear
          for { terms <- Option(reader.getTermVector(doc, F_CONTENT)) } {
            val te = terms.iterator
            if (te.seekExact(t.bytes)) {
              // TODO: use DocValues instead
              val d = ldoc(reader.document(doc))
              for { (pos, pe) <- LuceneUtil.postIter(te.postings(pe, PostingsEnum.OFFSETS)) } {  // includes POSITIONS
                posInfos += PosInfo(pos, pos + 1, pe.startOffset, pe.endOffset)
              }
              hitBuf += LPosDoc(d.idEmbIdx, posInfos.toList)
            }
          }
        }
        
        def hits = hitBuf.toList
      }
      
      class MySpanCollector extends SpanCollector {
        private var minPos = Int.MaxValue
        private var maxPos = Int.MinValue
        private var minOff = Int.MaxValue
        private var maxOff = Int.MinValue
        
        override def reset: Unit = {
          // log.debug(s"PosDocSearch.MySpanCollector.reset():")
          minPos = Int.MaxValue 
          maxPos = Int.MinValue
          minOff = Int.MaxValue 
          maxOff = Int.MinValue
        }
        
        override def collectLeaf(p: PostingsEnum, pos: Int, t: Term): Unit = {
          log.debug(s"PosDocSearch.MySpanCollector: docId = ${p.docID}, pos = $pos, t = ${t.text}, offsets: str = ${p.startOffset}, end = ${p.endOffset}")
          if (pos < minPos) minPos = pos
          if (pos + 1 > maxPos) maxPos = pos + 1
          if (p.startOffset < minOff) minOff = p.startOffset
          if (p.endOffset > maxOff) maxOff = p.endOffset
        }
        
        def posInfo = PosInfo(minPos, maxPos, minOff, maxOff)
      }
      
//      val searchSpansScoreTimer = Timer()
//      val searchSpansNonScoreTimer = Timer()
//      var searchSpansCount = 0
            
      /**
       * Terms from text using the analyzer for the F_CONTENT field.
       */
      def getTerms(text: String): Iterator[Term] = tokenIter(analyzer, F_CONTENT, text).map(new Term(F_CONTENT, _))
      
      /**
       * IDF score according to Lucene's formula: https://lucene.apache.org/core/7_1_0/core/org/apache/lucene/search/similarities/TFIDFSimilarity.html
       * Not using term freq or doc length norm etc. This depends only on the query not the matching doc.
       */
      def getScore(r: IndexReader)(terms: Iterator[Term]) = terms.foldLeft(0.0) { (score, t) => score + 1.0 + Math.log10( (r.numDocs + 1.0) / (r.docFreq(t) + 1.0)) }
      
      def searchSpans(searcher: IndexSearcher, slop: Int, q: PosQuery, minScore: Float): PHits = {
        val terms = getTerms(q.extRef.name).toList
        val score = getScore(searcher.getIndexReader)(terms.iterator)
        val noHits = PHits(Stats(0, 0.0f), List.empty, None, q.extRef, score, q.typ)

        if (score <= minScore) noHits 
        else if (terms.size > 1) searchSpansPhrase(searcher, slop, q, terms, score)
        else if (terms.size == 1) searchSpansTerm(searcher, q, terms.head, score)
        else noHits
      }
      
      /** 
       * this is a phrase search
       * a single term results in: java.lang.IllegalArgumentException: Less than 2 subSpans.size():1
       */
      def searchSpansPhrase(searcher: IndexSearcher, slop: Int, q: PosQuery, terms: List[Term], score: Double): PHits = {
        val timer = Timer()
        
        val snq = new SpanNearQuery(terms.map(new SpanTermQuery(_)).toArray, slop, q.typ == T_ORGANIZATION)
        log.debug(s"PosDocSearch.searchSpans: snq = $snq")
        
        val weight = snq.createWeight(searcher, false, 1.0f) // not needsScores
        val collector = new MySpanCollector
        val hits = (for {
          lrc <- searcher.getIndexReader.getContext.leaves.asScala
          dv = lrc.reader.getBinaryDocValues(F_ID_EMB_IDX)
          spans <- Option(weight.getSpans(lrc, SpanWeight.Postings.OFFSETS)).toList
          docId <- Iterator.continually(spans.nextDoc).takeWhile(_ !=  DocIdSetIterator.NO_MORE_DOCS)
          idEmbIdx <- if (dv.advanceExact(docId)) List(dv.binaryValue.utf8ToString.parseJson.convertTo[IdEmbIdx]) else List.empty
          // docId relative to lrc, not searcher.getIndexReader
          posns = Iterator.continually(spans.nextStartPosition).takeWhile(_ !=  Spans.NO_MORE_POSITIONS)
            .map { _ =>
              collector.reset
              spans.collect(collector)
              collector.posInfo
            }.filter(p => p.posEnd - p.posStr == terms.size).toList
            // handle duplicate terms in query - same num terms in query and match
            // e.g. prevents "Aaron H Aaron" from matching "H Aaron"
            // However this still allows "Aaron H Aaron" to match "Aaron H H"!
            // Don't see any "min must match" functionality.
            // We've worked hard not to have to actually fetch the doc content to keep it fast
            // but that's going to be required to solve this last issue.
            // In util --hits we have the doc content, so defer this last filtering step to there.
        } yield LPosDoc(idEmbIdx, posns)).filter(_.posInfos.nonEmpty).toList
//        searchSpansNonScoreTimer.stop
//        searchSpansCount += 1
//        if (searchSpansCount % 1000 == 0) log.info(s"searchSpans: scoring took ${searchSpansScoreTimer.elapsedSecs} sec, searching took ${searchSpansNonScoreTimer.elapsedSecs} sec")
        // Scoring is fast enough: scoring took 0.462 sec, searching took 113.58 sec
        timer.stop
        PHits(Stats(hits.size, timer.elapsedSecs), hits, None, q.extRef, score, q.typ)
      }
      
      def searchSpansTerm(searcher: IndexSearcher, q: PosQuery, term: Term, score: Double): PHits = {
        val timer = Timer()
        val tq = new TermQuery(term)
        log.debug(s"searchSpansTerm: TermQuery = $tq")
        val coll = new MyCollector(searcher.getIndexReader, term, score)
        // TODO: this isn't working
        searcher.search(tq, coll)
        val hits = coll.hits
        PHits(Stats(hits.size, timer.elapsedSecs), hits, None, q.extRef, score, q.typ)
      }
            
    }
  
    object MetaSearch {
      def toHit(scoreDoc: ScoreDoc, doc: Document) = (scoreDoc.score, doc.get(F_JSON).parseJson.convertTo[LMeta])
      
      def toResult(totalHits: Int, elapsedSecs: Float, hits: Seq[(Float, LMeta)], error: Option[String])
        = MHits(Stats(totalHits, elapsedSecs), hits.toList, error)        
    }
  
    object NerSearch {
      def toHit(scoreDoc: ScoreDoc, doc: Document) = (scoreDoc.score, doc.get(F_JSON).parseJson.convertTo[LNer])
      
      def toResult(totalHits: Int, elapsedSecs: Float, hits: Seq[(Float, LNer)], error: Option[String])
        = NHits(Stats(totalHits, elapsedSecs), hits.toList, error)
        
    }
  }

}