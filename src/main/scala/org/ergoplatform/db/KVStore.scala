package org.ergoplatform.db


import org.ergoplatform.utils.ByteArrayUtils
import org.iq80.leveldb.{DB, ReadOptions}

import scala.collection.mutable

trait KVStore extends AutoCloseable {

  val firstUnusedPrefix: Byte = 0

  type K = Array[Byte]
  type V = Array[Byte]

  protected val db: DB

  def get(key: K): Option[V] =
    Option(db.get(key))

  //todo: getAll used in tests only, remove
  def getAll(cond: (K, V) => Boolean): Seq[(K, V)] = {
    val ro = new ReadOptions()
    ro.snapshot(db.getSnapshot)
    val iter = db.iterator(ro)
    try {
      iter.seekToFirst()
      val bf = mutable.ArrayBuffer.empty[(K, V)]
      while (iter.hasNext) {
        val next = iter.next()
        val key = next.getKey
        val value = next.getValue
        if (cond(key, value)) bf += (key -> value)
      }
      bf.toList
    } finally {
      iter.close()
      ro.snapshot().close()
    }
  }

  def getAll: Seq[(K, V)] = getAll((_, _) => true)

  def getRange(start: K, end: K): Seq[(K, V)] = {
    val ro = new ReadOptions()
    ro.snapshot(db.getSnapshot)
    val iter = db.iterator(ro)
    try {
      def check(key:Array[Byte]) = {
        if (ByteArrayUtils.compare(key, end) <= 0) {
          true
        } else {
          false
        }
      }
      iter.seek(start)
      val bf = mutable.ArrayBuffer.empty[(K, V)]
      while (iter.hasNext && check(iter.peekNext.getKey)) {
        val next = iter.next()
        val key = next.getKey
        val value = next.getValue
        bf += (key -> value)
      }
      bf.toList
    } finally {
      iter.close()
      ro.snapshot().close()
    }
  }

  def getOrElse(key: K, default: => V): V =
    get(key).getOrElse(default)

  def get(keys: Seq[K]): Seq[(K, Option[V])] = {
    val bf = mutable.ArrayBuffer.empty[(K, Option[V])]
    keys.foreach(k => bf += (k -> get(k)))
    bf
  }

  override def close(): Unit = db.close()

}
