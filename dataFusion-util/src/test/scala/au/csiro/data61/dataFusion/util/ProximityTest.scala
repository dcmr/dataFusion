package au.csiro.data61.dataFusion.util

import org.scalatest.{ FlatSpec, Matchers }

import com.typesafe.scalalogging.Logger

import Main.GAZ
import au.csiro.data61.dataFusion.common.Data.{ Doc, Embedded, Ner, T_PERSON }

class ProximityTest extends FlatSpec with Matchers {
  val log = Logger(getClass)
  
  val posStr = 10
  val dist = 10
  
  // case class Ner(posStr: Int, posEnd: Int, offStr: Int, offEnd: Int, score: Double, text: String, typ: String, impl: String, extRefId: Option[List[Long]])
  val ner1 = Ner(posStr, 0, 0, 0, 1.0, "text", T_PERSON, GAZ, Some(List(1, 3)))
  val ner2 = Ner(posStr + dist, 0, 0, 0, 1.0, "text", T_PERSON, GAZ, Some(List(2, 4)))
  val ners = List(ner1, ner2)
  
  
  "Proximity" should "find close ners" in {
    // case class Doc(id: Long, content: Option[String], meta: Map[String, String], path: String, ner: List[Ner], embedded: List[Embedded])
    val doc = Doc(0, Some("text"), Map.empty, "path", ners, List.empty)
    val prox = new Proximity
    prox.accDoc(doc)
    for (x <- prox.nodeMap) log.info(s"$x")
    for (x <- prox.edgeMap) log.info(s"$x")
    prox.nodeMap.size should be(2)
    prox.edgeMap.size should be(1)
    prox.edgeMap.head should be(((1,2), Math.exp(-dist/20.0)))

    prox.accDoc(doc)
    for (x <- prox.nodeMap) log.info(s"$x")
    for (x <- prox.edgeMap) log.info(s"$x")
    prox.nodeMap.size should be(2)
    prox.edgeMap.size should be(1)
    prox.edgeMap.head should be(((1,2), 2*Math.exp(-dist/20.0)))

    // case class Embedded(content: Option[String], meta: Map[String, String], ner: List[Ner])
    val emb = Embedded(Some("text"), Map.empty, ners)
    val doc2 = doc.copy(embedded = List(emb))
    prox.accDoc(doc2)
    for (x <- prox.nodeMap) log.info(s"$x")
    for (x <- prox.edgeMap) log.info(s"$x")
    prox.nodeMap.size should be(2)
    prox.edgeMap.size should be(1)
    prox.edgeMap.head should be(((1,2), 4*Math.exp(-dist/20.0)))
  }
  
}