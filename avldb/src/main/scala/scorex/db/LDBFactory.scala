package scorex.db

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

import org.iq80.leveldb.{DB, Range, DBFactory, DBIterator, Options, ReadOptions, Snapshot, WriteBatch, WriteOptions}
import scorex.util.ScorexLogging

import scala.collection.mutable
import scala.util.Try

// Registry of opened LevelDB instances.
// LevelDB prohibit access to the same storage file from more than one DB instance.
// And ergo application (mostly tests) quit frequently doesn't not explicitly close
// database and tries to reopen it.
case class StoreRegistry(val factory : DBFactory) extends DBFactory {

  val lock = new ReentrantReadWriteLock()
  val map = new mutable.HashMap[File, RegisteredDB]

  // Decorator of LevelDB DB class which overrides close() methods and unlinks database from registry on close.
  case class RegisteredDB(val impl:DB, val path: File) extends DB {
    var count: Int = 0

    def get(key: Array[Byte]): Array[Byte] = impl.get(key)

    def get(key: Array[Byte], options: ReadOptions): Array[Byte] = impl.get(key, options)

    def iterator: DBIterator = impl.iterator

    def iterator(options: ReadOptions): DBIterator = impl.iterator(options)

    def put(key: Array[Byte], value: Array[Byte]) = impl.put(key, value)

    def delete(key: Array[Byte]) = impl.delete(key)

    def write(batch: WriteBatch) = impl.write(batch)

    def write(batch: WriteBatch, options: WriteOptions) = impl.write(batch, options)

    def createWriteBatch: WriteBatch = impl.createWriteBatch()

    def put(key: Array[Byte], value: Array[Byte], options: WriteOptions) = impl.put(key, value, options)

    def delete(key: Array[Byte], options: WriteOptions) = impl.delete(key, options)

    def getSnapshot: Snapshot = impl.getSnapshot()

    def getApproximateSizes(ranges: Range*): Array[Long] = impl.getApproximateSizes(ranges: _*)

    def getProperty(name: String): String = impl.getProperty(name)

    def suspendCompactions = impl.suspendCompactions()

    def resumeCompactions = impl.resumeCompactions()

    def compactRange(begin: Array[Byte], end: Array[Byte]) = impl.compactRange(begin, end)

    override def close() = {
      remove(path)
      impl.close()
    }
  }

  private def add(file: File, create: => DB): DB = {
    lock.writeLock().lock()
    try {
      map.getOrElseUpdate(file, new RegisteredDB(create, file))
    } finally {
      lock.writeLock().unlock()
    }
  }

  private def remove(path:File): Unit = {
    lock.writeLock().lock()
    try {
      map.remove(path)
    } finally {
      lock.writeLock().unlock()
    }
  }

  def open(path: File, options: Options): DB = {
    lock.writeLock().lock()
    try {
      add(path, factory.open(path, options))
    } finally {
      lock.writeLock().unlock()
    }
  }

  def destroy(path: File, options: Options) = {
    factory.destroy(path, options)
  }

  def repair(path: File, options: Options) = {
    factory.repair(path, options)
  }
}

object LDBFactory extends ScorexLogging {

  private val nativeFactory = "org.fusesource.leveldbjni.JniDBFactory"
  private val javaFactory   = "org.iq80.leveldb.impl.Iq80DBFactory"

  lazy val factory: DBFactory = {
    val loaders = List(ClassLoader.getSystemClassLoader, this.getClass.getClassLoader)
    val factories = List(nativeFactory, javaFactory)
    val pairs = loaders.view
      .zip(factories)
      .flatMap { case (loader, factoryName) =>
        loadFactory(loader, factoryName).map(factoryName -> _)
      }

    val (name, factory) = pairs.headOption.getOrElse(
      throw new RuntimeException(s"Could not load any of the factory classes: $nativeFactory, $javaFactory"))

    if (name == javaFactory) {
      log.warn("Using the pure java LevelDB implementation which is still experimental")
    } else {
      log.info(s"Loaded $name with $factory")
    }
    new StoreRegistry(factory)
  }

  private def loadFactory(loader: ClassLoader, factoryName: String): Option[DBFactory] =
    try Some(loader.loadClass(factoryName).getConstructor().newInstance().asInstanceOf[DBFactory])
    catch {
      case e: Throwable =>
        log.warn(s"Failed to load database factory $factoryName due to: $e")
        None
    }
}
