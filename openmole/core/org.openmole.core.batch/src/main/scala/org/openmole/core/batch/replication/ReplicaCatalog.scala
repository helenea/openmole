/*
 * Copyright (C) 2010 reuillon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openmole.core.batch.replication

import com.db4o.Db4o
import com.db4o.Db4oEmbedded
import com.db4o.EmbeddedObjectContainer
import com.db4o.ObjectContainer
import com.db4o.ObjectSet
import com.db4o.config.Configuration
import com.db4o.defragment.Defragment
import com.db4o.defragment.DefragmentConfig
import com.db4o.diagnostic.Diagnostic
import com.db4o.diagnostic.DiagnosticToConsole
import com.db4o.query.Predicate
import com.db4o.ta.TransparentPersistenceSupport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.Future
import java.util.logging.Level
import java.util.logging.Logger
import org.openmole.commons.tools.service.IHash
import org.openmole.commons.tools.io.FileUtil._
import org.openmole.misc.executorservice.ExecutorType
import org.openmole.core.batch.internal.Activator._
import org.openmole.commons.tools.service.ReadWriteLock
import org.openmole.core.batch.control.AccessToken
import org.openmole.core.batch.control.BatchServiceDescription
import org.openmole.core.batch.control.BatchStorageDescription
import org.openmole.core.batch.environment.BatchAuthenticationKey
import org.openmole.core.batch.environment.BatchAuthenticationKey
import org.openmole.core.batch.environment.BatchStorage
import org.openmole.core.batch.file.GZURIFile
import org.openmole.core.batch.file.URIFile
import org.openmole.core.batch.file.URIFileCleaner
import org.openmole.misc.workspace.ConfigurationLocation

import scala.collection.JavaConversions._
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer

object ReplicaCatalog {
  val LOGGER = Logger.getLogger(ReplicaCatalog.getClass.getName)

  
  val GCUpdateInterval = new ConfigurationLocation("ReplicaCatalog", "GCUpdateInterval");
  val ObjectRepoLocation = new ConfigurationLocation("ReplicaCatalog", "ObjectRepoLocation")
  workspace += (GCUpdateInterval, "PT5M")
  workspace += (ObjectRepoLocation, ".objectRepository.bin")
  
  lazy val dbFile = workspace.file(workspace.preference(ObjectRepoLocation))
  lazy val locks = new ReplicaLockRepository
  lazy val readWriteLock = new ReadWriteLock
  
  lazy val objectServer: EmbeddedObjectContainer = {
    val objRepo = workspace.file(workspace.preference(ObjectRepoLocation))
            
    if(objRepo.exists) defrag(objRepo)
    Db4oEmbedded.openFile(dB4oConfiguration, objRepo.getAbsolutePath)
  }
  
 
  def lockRead[A](op: => A): A = {
    readWriteLock.lockRead
    try {
      op
    } finally {
      readWriteLock.unlockRead
    }
  }
  
  def lockWrite[A](op: => A): A = {
    readWriteLock.lockWrite
    try {
      op
    } finally {
      readWriteLock.unlockWrite
    }
  }
  
  
 /* def transactionalOp[A](op: ObjectContainer => A): A = {
    val container = obj
   objectServer.synchronized {
   op(objectServer)
  }*/
  /* 
   val transaction = objectServer.openSession
   try {
   val ret = op(transaction)
   transaction.commit
   ret
   } finally {
   transaction.close
   }*/
  //}
  
  updater.registerForUpdate(new ReplicaCatalogGC, ExecutorType.OWN, workspace.preferenceAsDurationInMs(GCUpdateInterval))
 
  private def getReplica(hash: IHash, storageDescription: BatchStorageDescription, authenticationKey: BatchAuthenticationKey): Option[Replica] = {
    lockRead({
        val set = objectServer.queryByExample(new Replica(null, storageDescription.toString, hash, authenticationKey, null))
        if (!set.isEmpty) Some(set.get(0)) else None
      })
  }

  private def getReplica(src: File, hash: IHash, storageDescription: BatchStorageDescription,  authenticationKey: BatchAuthenticationKey): Option[Replica] = {
    lockRead({
        val set = objectServer.queryByExample(new Replica(src.getAbsolutePath, storageDescription.toString, hash, authenticationKey, null))

          return set.size match {
          case 0 => None
          case 1 => Some(set.get(0))
          case _ => Some(fix(set))
        }
      })
  }
    

  def getReplica(src: File, storageDescription: BatchStorageDescription, authenticationKey: BatchAuthenticationKey): ObjectSet[Replica] = {
    lockRead(objectServer.queryByExample(new Replica(src.getAbsolutePath, storageDescription.toString, null, authenticationKey, null)))
  }
  

  def isInCatalog(uri: String): Boolean = {
    lockRead(!objectServer.queryByExample(new Replica(null,null,null,null, uri)).isEmpty)
  }
  
  def inCatalog(src: Iterable[File], authenticationKey: BatchAuthenticationKey): Map[File, Set[BatchStorageDescription]] = {
    //transactionalOp( t => {
    if(src.isEmpty) return Map.empty
    lockRead({
        val query = objectServer.query
        query.constrain(classOf[Replica])

        query.descend("_authenticationKey").constrain(authenticationKey)
          .and(src.map{ f => query.descend("_source").constrain(f.getAbsolutePath) }.reduceLeft( (c1, c2) => c1.or(c2)))
               
        var ret = new HashMap[File, HashSet[BatchStorageDescription]] {
          override def default = HashSet.empty
        }
        
        query.execute[Replica].foreach {
          replica =>  ret.getOrElseUpdate(replica.sourceFile, new HashSet[BatchStorageDescription]) += replica.storageDescription
        }
        
        ret.map{ elt => (elt._1, elt._2.toSet) }.toMap
      })
    // })
  }
  
  
  //Synchronization should be achieved outiside the replica for database caching and isolation purposes
  def uploadAndGet(src: File, srcPath: File, hash: IHash, storage: BatchStorage, token: AccessToken): Replica = {
    //LOGGER.log(Level.INFO, "Looking for replica for {0} hash {1}.", Array(srcPath.getAbsolutePath, hash))
    val key = new ReplicaLockKey(hash, storage.description, storage.environment.authenticationKey) 
    
    locks.lock(key)

    try {
      val storageDescription = storage.description
      val authenticationKey = storage.environment.authenticationKey

      val replica = getReplica(srcPath, hash, storageDescription, authenticationKey) match {
        case None =>
    //      LOGGER.log(Level.INFO, "Not found Replica for {0}.", srcPath.getAbsolutePath + " " + storage)            
          for (toClean <- getReplica(srcPath, storageDescription, authenticationKey).iterator) clean(toClean)
                
          getReplica(hash, storageDescription, authenticationKey) match {
            case Some(sameContent) => 
              val newReplica = new Replica(srcPath.getAbsolutePath, storageDescription.description, hash, authenticationKey, sameContent.destination)
              insert(newReplica)
              newReplica
            case None =>
              val newFile = new GZURIFile(storage.persistentSpace(token).newFileInDir("replica", ".rep"))

              URIFile.copy(src, newFile, token)

              val newReplica = new Replica(srcPath.getAbsolutePath, storage.description.toString, hash, storage.environment.authenticationKey, newFile.location)
              insert(newReplica)
              newReplica 
          }
        case Some(r) => {
     //       LOGGER.log(Level.INFO, "Found Replica for {0}.", srcPath.getAbsolutePath + " " + storage)
            objectServer.activate(r, Int.MaxValue)
            r
          }
      }   
      replica
    } finally {
      locks.unlock(key)
    }
  }

  private def fix(toFix: Iterable[Replica]): Replica = {
    lockWrite({
        for(rep <- toFix.tail) objectServer.delete(rep)
        toFix.head
      })
  }

  def allReplicas:  Iterable[Replica] = {
    lockRead({ 
        val q = objectServer.query
        q.constrain(classOf[Replica])
        q.execute.toArray(Array[Replica]())
      })
  }

  private def insert(replica: Replica) = {
    lockWrite(
      { 
        try {
          objectServer.store(replica)
          //LOGGER.log(Level.INFO,"Insert " + replica.toString)
        } finally {
          objectServer.commit
        }
      })
  }

  def remove(replica: Replica) = synchronized {
    lockWrite({
        try {
          objectServer.delete(replica)
        } finally {
          objectServer.commit
        }
      })
  }

  def clean(replica: Replica): Future[_] = synchronized {
    LOGGER.log(Level.FINE, "Cleaning replica {0}", replica.toString)
    val ret = executorService.executorService(ExecutorType.REMOVE).submit(new URIFileCleaner(new URIFile(replica.destination)), false)
    remove(replica)
    ret
  }

  def cleanAll: Iterable[Future[_]] = synchronized {
    val ret = new ListBuffer[Future[_]]
    for (rep <- allReplicas) ret += clean(rep)
    ret
  }

  private def dB4oConfiguration = {
    val configuration = Db4oEmbedded.newConfiguration
    configuration.common.add(new TransparentPersistenceSupport)
    configuration.common.objectClass(classOf[Replica]).cascadeOnDelete(true)
    configuration.common.activationDepth(Int.MaxValue)

    /*configuration.common.diagnostic.addListener(new DiagnosticToConsole {	 
        override def onDiagnostic(diagnostic: Diagnostic) =  {
          LOGGER.log(Level.FINE, diagnostic.toString)
        }
      })*/
    //configuration.freespace.discardSmallerThan(50)

    configuration.common.objectClass(classOf[Replica]).objectField("_hash").indexed(true)
    configuration.common.objectClass(classOf[Replica]).objectField("_source").indexed(true)
    configuration.common.objectClass(classOf[Replica]).objectField("_storageDescription").indexed(true)
    configuration.common.objectClass(classOf[Replica]).objectField("_authenticationKey").indexed(true)
     
    configuration
  }

  private def defrag(db: File) = {
    val defragmentConfig = new DefragmentConfig(db.getAbsolutePath)
    defragmentConfig.forceBackupDelete(true)
    Defragment.defrag(defragmentConfig)
  }

}
