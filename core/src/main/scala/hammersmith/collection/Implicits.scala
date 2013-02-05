/**
 * Copyright (c) 2011-2013 Brendan W. McAdams <http://evilmonkeylabs.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package hammersmith.collection

import org.bson.io.OutputBuffer
import org.bson.types.ObjectId
import java.io.InputStream
import hammersmith.bson.util.Logging
import hammersmith.bson.util.ThreadLocal
import hammersmith.bson.{SerializableBSONObject, DefaultBSONDeserializer, DefaultBSONSerializer}

trait Imports extends Logging {
  // TODO - do we still need this, migrating into the Casbah code? -bwm feb-3-13
  // I dont think I can combine this with NotNothing...
  // The issue here is that asInstanceOf[A] doesn't use the
  // manifest and thus doesn't do anything (no runtime type
  // check). We have to use the manifest to cast by hand.
  // Surely there is an easier way to do this! If you know it,
  // please advise.
  protected[collection] def checkedCast[A <: Any: Manifest](value: Any): A = {
    try {
      // I could not tell you why we have to check both ScalaObject
      // and AnyRef here, but for example
      // manifest[BSONDocument] <:< manifest[AnyRef]
      // is false.
      if (manifest[A] <:< manifest[AnyRef] ||
        manifest[A] <:< manifest[ScalaObject]) {
        // casting to a boxed type
        manifest[A].erasure.asInstanceOf[Class[A]].cast(value)
      } else {
        // casting to an Any such as Int, we need boxed types to unpack,
        // which asInstanceOf does but Class.cast does not
        val asAnyVal = manifest[A] match {
          case m if m == manifest[Byte] => value.asInstanceOf[Byte]
          case m if m == manifest[Short] => value.asInstanceOf[Short]
          case m if m == manifest[Int] => value.asInstanceOf[Int]
          case m if m == manifest[Long] => value.asInstanceOf[Long]
          case m if m == manifest[Float] => value.asInstanceOf[Float]
          case m if m == manifest[Double] => value.asInstanceOf[Double]
          case m if m == manifest[Boolean] => value.asInstanceOf[Boolean]
          case m if m == manifest[Char] => value.asInstanceOf[Char]
          case m => throw new UnsupportedOperationException("Type " + manifest[A] + " not supported by getAs, value is: " + value)
        }
        asAnyVal.asInstanceOf[A]
      }
    } catch {
      case cc: ClassCastException =>
        log.debug("Error casting " +
          value.asInstanceOf[AnyRef].getClass.getName +
          " to " +
          manifest[A].erasure.getName)
        throw cc
    }
  }

  def defaultSerializer = new ThreadLocal(new DefaultBSONSerializer)

  def defaultDeserializer = new ThreadLocal(new DefaultBSONDeserializer) 

  trait SerializableBSONDocumentLike[T <: BSONDocument] extends SerializableBSONObject[T] with Logging {

    def encode(doc: T, out: OutputBuffer) = {
      log.trace("Reserving an encoder instance")
      val serializer = defaultSerializer()
      log.trace("Reserved an encoder instance")
      serializer.encode(doc, out)
      serializer.done
    }

    def encode(doc: T): Array[Byte] = {
      log.trace("Reserving an encoder instance")
      val serializer = defaultSerializer()
      log.trace("Reserved an encoder instance")
      val bytes = serializer.encode(doc)
      serializer.done
      bytes
    }

    def decode(in: InputStream): T = {
      val deserializer = defaultDeserializer()
      val doc = deserializer.decodeAndFetch(in).asInstanceOf[T]
      log.debug("DECODED DOC: %s as %s", doc, doc.getClass)
      doc
    }

    def checkObject(doc: T, isQuery: Boolean = false) = if (!isQuery) checkKeys(doc)

    def checkKeys(doc: T) {
      // TODO - Track key and level for clear error message?
      // TODO - Tail Call optimize me?
      // TODO - Optimize... trying to minimize number of loops but can we cut the instance checks?
      for (k ← doc.keys) {
        require(!(k contains "."), "Fields to be stored in MongoDB may not contain '.', which is a reserved character. Offending Key: " + k)
        require(!(k startsWith "$"), "Fields to be stored in MongoDB may not start with '$', which is a reserved character. Offending Key: " + k)
        if (doc.get(k).isInstanceOf[BSONDocument]) checkKeys(doc(k).asInstanceOf[T])
      }
    }

    /**
     * Checks for an ID and generates one.
     * Not all implementers will need this, but it gets invoked nonetheless
     * as a signal to BSONDocument, etc implementations to verify an id is there
     * and generate one if needed.
     */
    def checkID(doc: T): T = {
      doc.get("_id") match {
        case Some(oid: ObjectId) ⇒ {
          log.debug("Found an existing OID")
          oid.notNew()
          //oid
        }
        case Some(other) ⇒ {
          log.debug("Found a non-OID ID")
          //other
        }
        case None ⇒ {
          // TODO - Replace me with new ObjectID Implementation
          val oid = new ObjectId()
          log.trace("no ObjectId. Generated: %s", doc.get("_id"))
          doc + "_id" -> oid
        }
      }
      doc
    }


    def _id(doc: T): Option[AnyRef] = doc.getAs[AnyRef]("_id")

  }

}

object `package` extends Imports

object Implicits {


  // todo - can we do this with Object instead of inside implicits?
  implicit object SerializableBSONDocument extends SerializableBSONDocumentLike[BSONDocument]

  //implicit object SerializableBSONList extends SerializableBSONDocumentLike[BSONList]

  implicit object SerializableImmutableDocument extends SerializableBSONDocumentLike[hammersmith.collection.immutable.Document]

  implicit object SerializableImmutableOrderedDocument extends SerializableBSONDocumentLike[hammersmith.collection.immutable.OrderedDocument]

  //implicit object SerializableImmutableBSONList extends SerializableBSONDocumentLike[hammersmith.collection.immutable.BSONList]

  implicit object SerializableMutableDocument extends SerializableBSONDocumentLike[hammersmith.collection.mutable.Document]

  implicit object SerializableMutableOrderedDocument extends SerializableBSONDocumentLike[hammersmith.collection.mutable.OrderedDocument]

  //implicit object SerializableMutableBSONList extends SerializableBSONDocumentLike[hammersmith.collection.mutable.BSONList]
}

abstract class ValidBSONType[T]

// todo - refactor types for Hammersmith's
object ValidBSONType {
  implicit object BasicBSONList extends ValidBSONType[org.bson.types.BasicBSONList]
  implicit object BasicDBList extends ValidBSONType[com.mongodb.BasicDBList]
  implicit object Binary extends ValidBSONType[org.bson.types.Binary]
  implicit object BSONTimestamp extends ValidBSONType[org.bson.types.BSONTimestamp]
  implicit object Code extends ValidBSONType[org.bson.types.Code]
  implicit object CodeWScope extends ValidBSONType[org.bson.types.CodeWScope]
  implicit object ObjectId extends ValidBSONType[org.bson.types.ObjectId]
  implicit object Symbol extends ValidBSONType[org.bson.types.Symbol]
  implicit object BSONObject extends ValidBSONType[org.bson.BSONObject]
  implicit object BasicDBObject extends ValidBSONType[com.mongodb.BasicDBObject]
  implicit object DBObject extends ValidBSONType[com.mongodb.DBObject]
}

/**
 * Nice trick from Miles Sabin using ambiguity in implicit resolution to disallow Nothing
 */
sealed trait NotNothing[A]{
  type B
}
object NotNothing {
  implicit val nothing = new NotNothing[Nothing]{ type B = Any }
  implicit def notNothing[A] = new NotNothing[A]{ type B = A }
}

// vim: set ts=2 sw=2 sts=2 et:
