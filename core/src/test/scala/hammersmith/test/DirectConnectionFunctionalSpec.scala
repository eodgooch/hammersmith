
package hammersmith.test

import org.specs2.runner.JUnitRunner
import org.junit.runner.RunWith
import org.specs2.Specification
import hammersmith.util.Logging
import akka.actor.{Props, ActorSystem}
import hammersmith.{CommandRequest, QueryRequest, DirectMongoDBConnector}
import java.net.{InetSocketAddress, InetAddress}
import akka.testkit.TestActorRef
import hammersmith.collection.Implicits._
import hammersmith.collection.immutable.Document

@RunWith(classOf[JUnitRunner])
class DirectConnectionFunctionalSpec extends Specification with Logging {

  implicit val system = ActorSystem("direct-connection-test")
  val conn = system.actorOf(Props(classOf[DirectMongoDBConnector], new InetSocketAddress("localhost", 27017), true))

  def is = sequential ^
    "This is a functional specification testing the direct connection in Hammersmith" ^
    p ^
    "The Direct Connection for Hammersmith should " ^
      "Connect to MongoDB" ! testMongoDBConnection ^
      "Get a list of databases" ! testGetDatabases ^
  endp

  def testMongoDBConnection = {
    conn must not beNull
  }

  def testGetDatabases = {
    conn ! CommandRequest[Document]("admin", Document("listDatabases" -> 1))
    conn must not beNull
  }

}
